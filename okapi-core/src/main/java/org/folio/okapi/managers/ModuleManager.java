package org.folio.okapi.managers;

import org.folio.okapi.util.DepResolution;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import org.folio.okapi.bean.ModuleDescriptor;
import io.vertx.core.Vertx;
import io.vertx.core.json.Json;
import io.vertx.core.logging.Logger;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import org.folio.okapi.bean.Tenant;
import static org.folio.okapi.common.ErrorType.*;
import org.folio.okapi.common.ExtendedAsyncResult;
import org.folio.okapi.common.Failure;
import org.folio.okapi.common.OkapiLogger;
import org.folio.okapi.common.Success;
import org.folio.okapi.util.CompList;
import org.folio.okapi.util.LockedTypedMap1;
import org.folio.okapi.common.ModuleId;
import org.folio.okapi.common.Messages;
import org.folio.okapi.service.ModuleStore;

/**
 * Manages a list of modules known to Okapi's "/_/proxy". Maintains consistency
 * checks on module versions, etc. Stores them in the database too, if we have
 * one.
 */
public class ModuleManager {

  private final Logger logger = OkapiLogger.get();
  private TenantManager tenantManager = null;
  private String mapName = "modules";
  private LockedTypedMap1<ModuleDescriptor> modules
    = new LockedTypedMap1<>(ModuleDescriptor.class);
  private ModuleStore moduleStore;
  private Messages messages = Messages.getInstance();

  public ModuleManager(ModuleStore moduleStore) {
    this.moduleStore = moduleStore;
  }

  /**
   * Force the map to be local. Even in cluster mode, will use a local memory
   * map. This way, the node will not share tenants with the cluster, and can
   * not proxy requests for anyone but the superTenant, to the InternalModule.
   * Which is just enough to act in the deployment mode.
   */
  public void forceLocalMap() {
    mapName = null;
  }

  public void setTenantManager(TenantManager tenantManager) {
    this.tenantManager = tenantManager;
  }

  public void init(Vertx vertx, Handler<ExtendedAsyncResult<Void>> fut) {
    modules.init(vertx, mapName, ires -> {
      if (ires.failed()) {
        fut.handle(new Failure<>(ires.getType(), ires.cause()));
      } else {
        loadModules(fut);
      }
    });
  }

  /**
   * Load the modules from the database, if not already loaded.
   *
   * @param fut
   */
  private void loadModules(Handler<ExtendedAsyncResult<Void>> fut) {
    if (moduleStore == null) {
      fut.handle(new Success<>());
    } else {
      modules.size(kres -> {
        if (kres.failed()) {
          fut.handle(new Failure<>(INTERNAL, kres.cause()));
        } else if (kres.result() > 0) {
          logger.debug("Not loading modules, looks like someone already did");
          fut.handle(new Success<>());
        } else {
          moduleStore.getAll(mres -> {
            if (mres.failed()) {
              fut.handle(new Failure<>(mres.getType(), mres.cause()));
            } else {
              CompList<Void> futures = new CompList<>(INTERNAL);
              for (ModuleDescriptor md : mres.result()) {
                Future<Void> f = Future.future();
                modules.add(md.getId(), md, f::handle);
                futures.add(f);
              }
              futures.all(fut);
            }
          });
        }
      });
    }
  }

  public void enableAndDisableCheck(Tenant tenant,
    ModuleDescriptor modFrom, ModuleDescriptor modTo,
    Handler<ExtendedAsyncResult<Void>> fut) {

    getEnabledModules(tenant, gres -> {
      if (gres.failed()) {
        fut.handle(new Failure<>(gres.getType(), gres.cause()));
        return;
      }
      List<ModuleDescriptor> modlist = gres.result();
      HashMap<String, ModuleDescriptor> mods = new HashMap<>(modlist.size());
      for (ModuleDescriptor md : modlist) {
        mods.put(md.getId(), md);
      }
      if (modFrom != null) {
        mods.remove(modFrom.getId());
      }
      if (modTo != null) {
        ModuleDescriptor already = mods.get(modTo.getId());
        if (already != null) {
          fut.handle(new Failure<>(USER,
            "Module " + modTo.getId() + " already provided"));
          return;
        }
        mods.put(modTo.getId(), modTo);
      }
      String conflicts = DepResolution.checkAllConflicts(mods);
      String deps = DepResolution.checkAllDependencies(mods);
      if (conflicts.isEmpty() && deps.isEmpty()) {
        fut.handle(new Success<>());
      } else {
        fut.handle(new Failure<>(USER, conflicts + " " + deps));
      }
    });
  }

  /**
   * Create a module.
   *
   * @param md
   * @param fut
   */
  public void create(ModuleDescriptor md, boolean check, boolean preRelease, Handler<ExtendedAsyncResult<Void>> fut) {
    List<ModuleDescriptor> l = new LinkedList<>();
    l.add(md);
    createList(l, check, preRelease, fut);
  }

  /**
   * Create a whole list of modules.
   *
   * @param list
   * @param fut
   */
  public void createList(List<ModuleDescriptor> list, boolean check, boolean preRelease, Handler<ExtendedAsyncResult<Void>> fut) {
    getModulesWithFilter(preRelease, true, ares -> {
      if (ares.failed()) {
        fut.handle(new Failure<>(ares.getType(), ares.cause()));
        return;
      }
      Map<String,ModuleDescriptor> tempList = new HashMap<>();
      for (ModuleDescriptor md : ares.result()) {
        tempList.put(md.getId(), md);
      }
      LinkedList<ModuleDescriptor> nList = new LinkedList<>();
      for (ModuleDescriptor md : list) {
        final String id = md.getId();
        if (tempList.containsKey(id)) {
          ModuleDescriptor exMd = tempList.get(id);

          String exJson = Json.encodePrettily(exMd);
          String json = Json.encodePrettily(md);
          if (!json.equals(exJson)) {
            fut.handle(new Failure<>(USER, messages.getMessage("10203", id)));
            return;
          }
        } else {
          tempList.put(id, md);
          nList.add(md);
        }
      }
      if (check) {
        String res = DepResolution.checkAllDependencies(tempList);
        if (!res.isEmpty()) {
          fut.handle(new Failure<>(USER, res));
          return;
        }
      }
      createList2(nList, fut);
    });
  }

  private void createList2(List<ModuleDescriptor> list, Handler<ExtendedAsyncResult<Void>> fut) {
    CompList<Void> futures = new CompList<>(INTERNAL);
    for (ModuleDescriptor md : list) {
      Future<Void> f = Future.future();
      createList3(md, f::handle);
      futures.add(f);
    }
    futures.all(fut);
  }

  private void createList3(ModuleDescriptor md, Handler<ExtendedAsyncResult<Void>> fut) {
    String id = md.getId();
    if (moduleStore == null) {
      modules.add(id, md, ares -> {
        if (ares.failed()) {
          fut.handle(new Failure<>(ares.getType(), ares.cause()));
          return;
        }
        fut.handle(new Success<>());
      });
    } else {
      moduleStore.insert(md, ires -> {
        if (ires.failed()) {
          fut.handle(new Failure<>(ires.getType(), ires.cause()));
          return;
        }
        modules.add(id, md, ares -> {
          if (ares.failed()) {
            fut.handle(new Failure<>(ares.getType(), ares.cause()));
            return;
          }
          fut.handle(new Success<>());
        });
      });
    }
  }

  /**
   * Update a module.
   *
   * @param md
   * @param fut
   */
  public void update(ModuleDescriptor md, Handler<ExtendedAsyncResult<Void>> fut) {
    final String id = md.getId();
    modules.getAll(ares -> {
      if (ares.failed()) {
        fut.handle(new Failure<>(ares.getType(), ares.cause()));
        return;
      }
      LinkedHashMap<String, ModuleDescriptor> tempList = ares.result();
      tempList.put(id, md);
      String res = DepResolution.checkAllDependencies(tempList);
      if (!res.isEmpty()) {
        fut.handle(new Failure<>(USER, messages.getMessage("10204", id, res)));
        return;
      }
      tenantManager.getModuleUser(id, gres -> {
        if (gres.failed()) {
          if (gres.getType() == ANY) {
            String ten = gres.cause().getMessage();
            fut.handle(new Failure<>(USER, messages.getMessage("10205", id, ten)));
          } else { // any other error
            fut.handle(new Failure<>(gres.getType(), gres.cause()));
          }
          return;
        }
        // all ok, we can update it
        if (moduleStore == null) { // no db, just upd shared memory
          modules.put(id, md, fut);
        } else {
          moduleStore.update(md, ures -> { // store in db first,
            if (ures.failed()) {
              fut.handle(new Failure<>(ures.getType(), ures.cause()));
            } else {
              modules.put(id, md, fut);
            }
          });
        }
      }); // getModuleUser
    }); // get
  }

  /**
   * Delete a module.
   *
   * @param id
   * @param fut
   */
  public void delete(String id, Handler<ExtendedAsyncResult<Void>> fut) {
    modules.getAll(ares -> {
      if (ares.failed()) {
        fut.handle(new Failure<>(ares.getType(), ares.cause()));
        return;
      }
      if (deleteCheckDep(id, fut, ares.result())) {
        return;
      }
      tenantManager.getModuleUser(id, ures -> {
        if (ures.failed()) {
          if (ures.getType() == ANY) {
            String ten = ures.cause().getMessage();
            fut.handle(new Failure<>(USER, messages.getMessage("10209", id, ten)));
            fut.handle(new Failure<>(USER, messages.getMessage("10206", id, ten)));
          } else {
            fut.handle(new Failure<>(ures.getType(), ures.cause()));
          }
        } else if (moduleStore == null) {
          deleteInternal(id, fut);
        } else {
          moduleStore.delete(id, dres -> {
            if (dres.failed()) {
              fut.handle(new Failure<>(dres.getType(), dres.cause()));
            } else {
              deleteInternal(id, fut);
            }
          });
        }
      });
    });
  }

  private boolean deleteCheckDep(String id, Handler<ExtendedAsyncResult<Void>> fut,
    LinkedHashMap<String, ModuleDescriptor> mods) {

    if (!mods.containsKey(id)) {
      fut.handle(new Failure<>(NOT_FOUND, messages.getMessage("10207")));
      return true;
    }
    mods.remove(id);
    String res = DepResolution.checkAllDependencies(mods);
    if (!res.isEmpty()) {
      fut.handle(new Failure<>(USER, messages.getMessage("10208", id, res)));
      return true;
    } else {
      return false;
    }
  }

  private void deleteInternal(String id, Handler<ExtendedAsyncResult<Void>> fut) {
    modules.remove(id, rres -> {
      if (rres.failed()) {
        fut.handle(new Failure<>(rres.getType(), rres.cause()));
      } else {
        fut.handle(new Success<>());
      }
    });
  }

  /**
   * Get a module.
   *
   * @param id to get. If null, returns a null.
   * @param fut
   */
  public void get(String id, Handler<ExtendedAsyncResult<ModuleDescriptor>> fut) {
    if (id != null) {
      modules.get(id, fut);
    } else {
      fut.handle(new Success<>(null));
    }
  }

  public void getLatest(String id, Handler<ExtendedAsyncResult<ModuleDescriptor>> fut) {
    ModuleId moduleId = new ModuleId(id);
    if (moduleId.hasSemVer()) {
      get(id, fut);
    } else {
      modules.getKeys(res -> {
        if (res.failed()) {
          fut.handle(new Failure<>(res.getType(), res.cause()));
        } else {
          String latest = moduleId.getLatest(res.result());
          get(latest, fut);
        }
      });
    }
  }

  public void getModulesWithFilter(boolean preRelease, boolean npmSnapshot,
    Handler<ExtendedAsyncResult<List<ModuleDescriptor>>> fut) {
    modules.getAll(kres -> {
      if (kres.failed()) {
        fut.handle(new Failure<>(kres.getType(), kres.cause()));
      } else {       
        List<ModuleDescriptor> mdl = new LinkedList<>();
        for (ModuleDescriptor md : kres.result().values()) {
          String id = md.getId();
          ModuleId idThis = new ModuleId(id);
          if ((npmSnapshot || !idThis.hasNpmSnapshot())
            && (preRelease || !idThis.hasPreRelease())) {
            mdl.add(md);
          }
        }
        fut.handle(new Success<>(mdl));
      }
    });
  }

  /**
   * Get all modules that are enabled for the given tenant.
   *
   * @param ten tenant to check for
   * @param fut callback with a list of ModuleDescriptors (may be empty list)
   */
  public void getEnabledModules(Tenant ten,
    Handler<ExtendedAsyncResult<List<ModuleDescriptor>>> fut) {

    List<ModuleDescriptor> mdl = new LinkedList<>();
    CompList<List<ModuleDescriptor>> futures = new CompList<>(INTERNAL);
    for (String id : ten.getEnabled().keySet()) {
      Future<ModuleDescriptor> f = Future.future();
      modules.get(id, res -> {
        if (res.succeeded()) {
          mdl.add(res.result());
        }
        f.handle(res);
      });
      futures.add(f);
    }
    futures.all(mdl, fut);
  }
}
