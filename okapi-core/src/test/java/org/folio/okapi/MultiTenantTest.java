package org.folio.okapi;

import io.restassured.RestAssured;
import static io.restassured.RestAssured.given;
import io.restassured.response.Response;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.folio.okapi.common.OkapiLogger;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.Assert;
import org.junit.runner.RunWith;

@java.lang.SuppressWarnings({"squid:S1192"})
@RunWith(VertxUnitRunner.class)
public class MultiTenantTest {

  private final Logger logger = OkapiLogger.get();
  private final int port = 9230;

  private Vertx vertx;
  private static final String LS = System.lineSeparator();

  final String docAuthModule = "{" + LS
    + "  \"id\" : \"auth-1\"," + LS
    + "  \"name\" : \"auth\"," + LS
    + "  \"provides\" : [ {" + LS
    + "    \"id\" : \"auth\"," + LS
    + "    \"version\" : \"1.2\"," + LS
    + "    \"handlers\" : [ {" + LS
    + "      \"methods\" : [ \"POST\" ]," + LS
    + "      \"path\" : \"/authn/login\"," + LS
    + "      \"level\" : \"20\"," + LS
    + "      \"type\" : \"request-response\"" + LS
    + "    } ]" + LS
    + "  } ]," + LS
    + "  \"filters\" : [ {" + LS
    + "    \"methods\" : [ \"*\" ]," + LS
    + "    \"path\" : \"/\"," + LS
    + "    \"phase\" : \"auth\"," + LS
    + "    \"type\" : \"request-response\"," + LS
    + "    \"permissionsDesired\" : [ \"auth.extra\" ]" + LS
    + "  } ]," + LS
    + "  \"requires\" : [ ]," + LS
    + "  \"launchDescriptor\" : {" + LS
    + "    \"exec\" : "
    + "\"java -Dport=%p -jar ../okapi-test-auth-module/target/okapi-test-auth-module-fat.jar\"" + LS
    + "  }" + LS
    + "}";

  final String docTestModule = "{" + LS
    + "  \"id\" : \"sample-module-1.2.0\"," + LS
    + "  \"name\" : \"this module\"," + LS
    + "  \"provides\" : [ {" + LS
    + "    \"id\" : \"_tenant\"," + LS
    + "    \"version\" : \"1.1\"," + LS
    + "    \"interfaceType\" : \"system\"," + LS
    + "    \"handlers\" : [ {" + LS
    + "      \"methods\" : [ \"POST\", \"DELETE\" ]," + LS
    + "      \"pathPattern\" : \"/_/tenant\"" + LS
    + "    } ]" + LS
    + "  }, {" + LS
    + "    \"id\" : \"myint\"," + LS
    + "    \"version\" : \"1.0\"," + LS
    + "    \"handlers\" : [ {" + LS
    + "      \"methods\" : [ \"GET\", \"POST\" ]," + LS
    + "      \"pathPattern\" : \"/testb\"" + LS
    + "    } ]" + LS
    + "  } ]," + LS
    + "  \"requires\" : [ ]," + LS
    + "  \"launchDescriptor\" : {" + LS
    + "    \"exec\" : "
    + "\"java -Dport=%p -jar ../okapi-test-module/target/okapi-test-module-fat.jar\"" + LS
    + "  }" + LS
    + "}";

  final String docTestModule2 = "{" + LS
    + "  \"id\" : \"sample-module-2.0.0\"," + LS
    + "  \"name\" : \"this module\"," + LS
    + "  \"provides\" : [ {" + LS
    + "    \"id\" : \"_tenant\"," + LS
    + "    \"version\" : \"1.1\"," + LS
    + "    \"interfaceType\" : \"system\"," + LS
    + "    \"handlers\" : [ {" + LS
    + "      \"methods\" : [ \"POST\", \"DELETE\" ]," + LS
    + "      \"pathPattern\" : \"/_/tenant\"" + LS
    + "    } ]" + LS
    + "  }, {" + LS
    + "    \"id\" : \"myint\"," + LS
    + "    \"version\" : \"1.0\"," + LS
    + "    \"handlers\" : [ {" + LS
    + "      \"methods\" : [ \"GET\", \"POST\" ]," + LS
    + "      \"pathPattern\" : \"/testb\"" + LS
    + "    } ]" + LS
    + "  } ]," + LS
    + "  \"requires\" : [ ]" + LS
    + "}";

  public MultiTenantTest() {
  }

  @Before
  public void setUp(TestContext context) {
    Async async = context.async();

    RestAssured.port = port;
    JsonObject conf = new JsonObject();
    conf.put("port", Integer.toString(port));

    MainDeploy d = new MainDeploy(conf);
    String[] args = {"cluster"};
    d.init(args, res -> {
      if (res.succeeded()) {
        vertx = res.result();
      }
      context.assertTrue(res.succeeded());
      async.complete();
    });
  }

  @After
  public void tearDown(TestContext context) {
    Async async = context.async();
    vertx.close(x -> {
      async.complete();
    });
  }

  @Test
  public void test1() {
    Response res;
    JsonArray ja;

    given()
      .header("Content-Type", "application/json")
      .body(docAuthModule)
      .post("/_/proxy/modules")
      .then().statusCode(201).log().ifValidationFails();

    given()
      .header("Content-Type", "application/json")
      .body(docTestModule)
      .post("/_/proxy/modules")
      .then().statusCode(201).log().ifValidationFails();

    given()
      .header("Content-Type", "application/json")
      .body(docTestModule2)
      .post("/_/proxy/modules")
      .then().statusCode(201).log().ifValidationFails();

    final String supertenant = "supertenant";
    given()
      .header("Content-Type", "application/json")
      .body("[ {\"id\" : \"auth-1\", \"action\" : \"enable\"} ]")
      .post("/_/proxy/tenants/" + supertenant + "/install?deploy=true")
      .then().statusCode(200).log().ifValidationFails()
      .body(equalTo("[ {" + LS
        + "  \"id\" : \"auth-1\"," + LS
        + "  \"action\" : \"enable\"" + LS
        + "} ]"));

    final String tenant1 = "tenant1";
    final String docTenant1 = "{" + LS
      + "  \"id\" : \"" + tenant1 + "\"," + LS
      + "  \"name\" : \"" + tenant1 + "\"," + LS
      + "  \"description\" : \"" + tenant1 + " bibliotek\"" + LS
      + "}";

    // create tenant should fail.. Must login first
    given()
      .header("Content-Type", "application/json")
      .body(docTenant1)
      .post("/_/proxy/tenants")
      .then().statusCode(401)
      .log().ifValidationFails();

    // supertenant login and get token
    final String docLoginSupertenant = "{" + LS
      + "  \"tenant\" : \"" + supertenant + "\"," + LS
      + "  \"username\" : \"peter\"," + LS
      + "  \"password\" : \"peter-password\"" + LS
      + "}";
    final String okapiTokenSupertenant = given()
      .header("Content-Type", "application/json").body(docLoginSupertenant)
      .post("/authn/login")
      .then().statusCode(200).extract().header("X-Okapi-Token");

    // create tenant1
    given()
      .header("Content-Type", "application/json")
      .header("X-Okapi-Token", okapiTokenSupertenant)
      .body(docTenant1)
      .post("/_/proxy/tenants")
      .then().statusCode(201)
      .header("Location", containsString("/_/proxy/tenants"))
      .log().ifValidationFails();

    final String tenant2 = "tenant2";
    final String docTenant2 = "{" + LS
      + "  \"id\" : \"" + tenant2 + "\"," + LS
      + "  \"name\" : \"" + tenant2 + "\"," + LS
      + "  \"description\" : \"" + tenant2 + " bibliotek\"" + LS
      + "}";

    // create tenant2
    given()
      .header("Content-Type", "application/json")
      .header("X-Okapi-Token", okapiTokenSupertenant)
      .body(docTenant2)
      .post("/_/proxy/tenants")
      .then().statusCode(201)
      .header("Location", containsString("/_/proxy/tenants"))
      .log().ifValidationFails();

    // enable+deploy sample-module-1.2.0 for tenant1
    given()
      .header("Content-Type", "application/json")
      .header("X-Okapi-Token", okapiTokenSupertenant)
      .body("[ {\"id\" : \"sample-module-1.2.0\", \"action\" : \"enable\"} ]")
      .post("/_/proxy/tenants/" + tenant1 + "/install?deploy=true")
      .then().statusCode(200).log().ifValidationFails()
      .body(equalTo("[ {" + LS
        + "  \"id\" : \"sample-module-1.2.0\"," + LS
        + "  \"action\" : \"enable\"" + LS
        + "} ]"));

    // enable+deploy auth-1 for tenant2
    given()
      .header("Content-Type", "application/json")
      .header("X-Okapi-Token", okapiTokenSupertenant)
      .body("[ {\"id\" : \"auth-1\", \"action\" : \"enable\"} ]")
      .post("/_/proxy/tenants/" + tenant2 + "/install?deploy=true")
      .then().statusCode(200).log().ifValidationFails()
      .body(equalTo("[ {" + LS
        + "  \"id\" : \"auth-1\"," + LS
        + "  \"action\" : \"enable\"" + LS
        + "} ]"));

    res = given()
      .header("Content-Type", "application/json")
      .header("X-Okapi-Token", okapiTokenSupertenant)
      .get("/_/discovery/modules")
      .then().statusCode(200).log().ifValidationFails()
      .extract().response();
    ja = new JsonArray(res.body().asString());
    Assert.assertEquals(2, ja.size());

    // tenant2 login and get token
    final String docLoginTenant2 = "{" + LS
      + "  \"tenant\" : \"" + tenant2 + "\"," + LS
      + "  \"username\" : \"peter\"," + LS
      + "  \"password\" : \"peter-password\"" + LS
      + "}";
    final String okapiTokenTenant2 = given()
      .header("Content-Type", "application/json").body(docLoginTenant2)
      .header("X-Okapi-Tenant", tenant2)
      .post("/authn/login")
      .then().statusCode(200).extract().header("X-Okapi-Token");

    given()
      .header("Content-Type", "application/json")
      .header("X-Okapi-Token", okapiTokenSupertenant)
      .body("[ {\"id\" : \"okapi-0.0.0\", \"action\" : \"enable\"} ]")
      .post("/_/proxy/tenants/" + tenant2 + "/install?deploy=true")
      .then().statusCode(200).log().ifValidationFails()
      .body(equalTo("[ {" + LS
        + "  \"id\" : \"okapi-0.0.0\"," + LS
        + "  \"action\" : \"enable\"" + LS
        + "} ]"));

    // test failure for enable+deploy sample-module-2.0.0 for tenant2 as tenant2
    // because no launch descriptor for sample-module-2.0.0
    given()
      .header("Content-Type", "application/json")
      .header("X-Okapi-Token", okapiTokenTenant2)
      .body("[ {\"id\" : \"sample-module-2.0.0\", \"action\" : \"enable\"} ]")
      .post("/_/proxy/tenants/" + tenant2 + "/install?deploy=true")
      .then().statusCode(400).log().ifValidationFails();

    // remedy the situation by adding launchDescriptor to sample-module-2.0.0
    JsonObject mod1 = new JsonObject(docTestModule);
    JsonObject mod2 = new JsonObject(docTestModule2);
    mod2.put("launchDescriptor", mod1.getJsonObject("launchDescriptor"));

    // deploy again .. should succeed
    given()
      .header("Content-Type", "application/json")
      .header("X-Okapi-Token", okapiTokenSupertenant)
      .body(mod2.encodePrettily())
      .put("/_/proxy/modules/sample-module-2.0.0")
      .then().statusCode(200).log().ifValidationFails();

    // enable+deploy sample-module-2.0.0 for tenant2 as tenant2
    given()
      .header("Content-Type", "application/json")
      .header("X-Okapi-Token", okapiTokenTenant2)
      .body("[ {\"id\" : \"sample-module-2.0.0\", \"action\" : \"enable\"} ]")
      .post("/_/proxy/tenants/" + tenant2 + "/install?deploy=true")
      .then().statusCode(200).log().ifValidationFails();

    res = given()
      .header("Content-Type", "application/json")
      .header("X-Okapi-Token", okapiTokenSupertenant)
      .get("/_/discovery/modules")
      .then().statusCode(200).log().ifValidationFails()
      .extract().response();
    logger.info(res.body().asString());
    ja = new JsonArray(res.body().asString());
    Assert.assertEquals(3, ja.size()); // two sample modules and auth module

    // undeploy sample-module-1.2.0
    given()
      .header("Content-Type", "application/json")
      .header("X-Okapi-Token", okapiTokenSupertenant)
      .body("[ {\"id\" : \"sample-module-1.2.0\", \"action\" : \"disable\"} ]")
      .post("/_/proxy/tenants/" + tenant1 + "/install?deploy=true")
      .then().statusCode(200).log().ifValidationFails();

    given()
      .header("Content-Type", "application/json")
      .header("X-Okapi-Token", okapiTokenSupertenant)
      .body("[ {\"id\" : \"sample-module-2.0.0\", \"action\" : \"disable\"} ]")
      .post("/_/proxy/tenants/" + tenant2 + "/install?deploy=true")
      .then().statusCode(200).log().ifValidationFails();

    res = given()
      .header("Content-Type", "application/json")
      .header("X-Okapi-Token", okapiTokenSupertenant)
      .get("/_/discovery/modules")
      .then().statusCode(200).log().ifValidationFails()
      .extract().response();
    ja = new JsonArray(res.body().asString());
    Assert.assertEquals(1, ja.size());

    // undeploy auth-1 for supertenant
    given()
      .header("Content-Type", "application/json")
      .header("X-Okapi-Token", okapiTokenSupertenant)
      .body("[ {\"id\" : \"auth-1\", \"action\" : \"disable\"} ]")
      .post("/_/proxy/tenants/" + supertenant + "/install?deploy=true")
      .then().statusCode(200).log().ifValidationFails();

    res = given()
      .header("Content-Type", "application/json")
      .header("X-Okapi-Token", okapiTokenSupertenant)
      .get("/_/discovery/modules")
      .then().statusCode(200).log().ifValidationFails()
      .extract().response();
    ja = new JsonArray(res.body().asString());
    Assert.assertEquals(1, ja.size());

    // undeploy auth-1 for tenant2
    given()
      .header("Content-Type", "application/json")
      .header("X-Okapi-Token", okapiTokenSupertenant)
      .body("[ {\"id\" : \"auth-1\", \"action\" : \"disable\"} ]")
      .post("/_/proxy/tenants/" + tenant2 + "/install?deploy=true")
      .then().statusCode(200).log().ifValidationFails();

    res = given()
      .header("Content-Type", "application/json")
      .header("X-Okapi-Token", okapiTokenSupertenant)
      .get("/_/discovery/modules")
      .then().statusCode(200).log().ifValidationFails()
      .extract().response();
    ja = new JsonArray(res.body().asString());
    Assert.assertEquals(0, ja.size());
  }
}
