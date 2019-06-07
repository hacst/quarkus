package io.quarkus.hibernate.orm.sql_load_script;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.deployment.configuration.ConfigurationError;
import io.quarkus.hibernate.orm.MyEntity;
import io.quarkus.test.QuarkusUnitTest;

public class SqlLoadScriptFileAbsentTestCase {
    @RegisterExtension
    static QuarkusUnitTest runner = new QuarkusUnitTest()
            .setExpectedException(ConfigurationError.class)
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class)
                    .addClasses(MyEntity.class)
                    .addAsResource("application-other-load-script-test.properties", "application.properties"));

    @Test
    public void testSqlLoadScriptFileAbsentTest() {
        // should not be called, deployment exception should happen first:
        // it's illegal to have Hibernate sql-load-script configuration property set
        // to an absent file
        Assertions.fail();
    }
}
