package nmk_backend.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.ActiveProfiles;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("mysql-test")
class PosExchangeMigrationMySqlTest {
 @Autowired private DataSource dataSource;
 @Value("${spring.datasource.url}") private String sourceUrl;
 @Value("${spring.datasource.username}") private String username;
 @Value("${spring.datasource.password}") private String password;

 @Test
 void manualExchangeMigrationCreatesConstraintsAndAcceptsValidSettlement() throws Exception {
  String schema="nmk_pos_exch_mig_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
  try(Connection connection=dataSource.getConnection(); Statement statement=connection.createStatement()) {
   statement.execute("CREATE DATABASE `"+schema+"`");
  }
  int queryIndex = sourceUrl.indexOf('?');
  int databaseStart = sourceUrl.lastIndexOf(
          '/',
          queryIndex >= 0 ? queryIndex : sourceUrl.length()
  );

  if (databaseStart < 0) {
   throw new IllegalStateException("Unable to determine database name from JDBC URL");
  }

  String targetUrl = sourceUrl.substring(0, databaseStart + 1)
          + schema
          + (queryIndex >= 0 ? sourceUrl.substring(queryIndex) : "");
  DriverManagerDataSource target=new DriverManagerDataSource(targetUrl,username,password);
  JdbcTemplate jdbc=new JdbcTemplate(target);
  try {
   jdbc.execute("CREATE TABLE pos_sales (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY) ENGINE=InnoDB");
   jdbc.execute("CREATE TABLE pos_returns (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY) ENGINE=InnoDB");
   jdbc.execute("CREATE TABLE pos_shifts (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY) ENGINE=InnoDB");
   new ResourceDatabasePopulator(new ClassPathResource("db/manual/014-create-pos-exchanges.sql")).execute(target);

   jdbc.update("INSERT INTO pos_sales(id) VALUES (1),(2),(3),(4)");
   jdbc.update("INSERT INTO pos_returns(id) VALUES (1),(2)");
   jdbc.update("INSERT INTO pos_shifts(id) VALUES (1)");
   jdbc.update("""
    INSERT INTO pos_exchanges
    (id,original_sale_id,return_id,replacement_sale_id,shift_id,processed_by_username,reason,
     returned_value,replacement_value,credit_applied,actual_refund,amount_collected,idempotency_key,request_fingerprint)
    VALUES (1,1,1,2,1,'migration-test','Higher value exchange',
     100.00,150.00,100.00,0.00,50.00,'migration-valid-key',REPEAT('a',64))
    """);
   jdbc.update("INSERT INTO pos_exchange_credit_settlements(exchange_id,replacement_sale_id,amount) VALUES (1,2,100.00)");
   assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM pos_exchanges",Integer.class));
   assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM pos_exchange_credit_settlements",Integer.class));

   assertThrows(DataAccessException.class, () -> jdbc.update("""
    INSERT INTO pos_exchanges
    (original_sale_id,return_id,replacement_sale_id,shift_id,processed_by_username,reason,
     returned_value,replacement_value,credit_applied,actual_refund,amount_collected,idempotency_key,request_fingerprint)
    VALUES (3,2,4,1,'migration-test','Invalid settlement equation',
     100.00,150.00,100.00,0.00,49.00,'migration-invalid-key',REPEAT('b',64))
    """), "The migration must enforce the settlement equations in MySQL");
  } finally {
   try(Connection connection=dataSource.getConnection(); Statement statement=connection.createStatement()) {
    statement.execute("DROP DATABASE IF EXISTS `"+schema+"`");
   }
  }
 }
}
