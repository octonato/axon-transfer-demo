package com.example.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.transfer.application.WalletRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.axonframework.conversion.DelegatingGeneralConverter;
import org.axonframework.conversion.GeneralConverter;
import org.axonframework.conversion.jackson.JacksonConverter;
import org.axonframework.extension.springboot.autoconfig.JdbcAutoConfiguration;
import org.axonframework.messaging.eventhandling.processing.streaming.token.GlobalSequenceTrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.jdbc.JdbcTokenStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.dao.PersistenceExceptionTranslationAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.sql.init.SqlInitializationAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class TokenStoreConfigurationTest {
  @TempDir Path directory;

  @Test
  void autoConfiguresTokenStoreAndRetainsProgressAfterRestart() {
    var runner = new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
            SqlInitializationAutoConfiguration.class, JdbcAutoConfiguration.class,
            PersistenceExceptionTranslationAutoConfiguration.class))
        .withBean(GeneralConverter.class,
            () -> new DelegatingGeneralConverter(new JacksonConverter()))
        .withBean(ObjectMapper.class, ObjectMapper::new)
        .withUserConfiguration(WalletRepository.class)
        .withPropertyValues("spring.datasource.url=jdbc:h2:file:" + directory.resolve("tokens"),
            "demo.wallet-directory=" + directory.resolve("wallets"));
    var token = new GlobalSequenceTrackingToken(42);

    runner.run(context -> {
      assertThat(context).hasNotFailed();
      assertThat(context.getBean(WalletRepository.class).getBalance("a")).isEqualTo(10_000);
      var store = context.getBean("tokenStore", TokenStore.class);
      assertThat(store).isInstanceOf(JdbcTokenStore.class);
      store.initializeTokenSegments("SimpleWorkflowContext", 1, token, null).join();
    });

    runner.run(context -> {
      assertThat(context).hasNotFailed();
      var store = context.getBean("tokenStore", TokenStore.class);
      assertThat(store.fetchToken("SimpleWorkflowContext", 0, null).join()).isEqualTo(token);
      store.releaseClaim("SimpleWorkflowContext", 0, null).join();
    });
  }
}
