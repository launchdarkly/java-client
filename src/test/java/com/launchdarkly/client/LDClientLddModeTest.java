package com.launchdarkly.client;

import com.launchdarkly.client.value.LDValue;

import org.junit.Test;

import java.io.IOException;

import static com.launchdarkly.client.TestUtil.flagWithValue;
import static com.launchdarkly.client.TestUtil.initedFeatureStore;
import static com.launchdarkly.client.TestUtil.specificFeatureStore;
import static com.launchdarkly.client.VersionedDataKind.FEATURES;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@SuppressWarnings("javadoc")
public class LDClientLddModeTest {
  @Test
  public void lddModeClientHasNullUpdateProcessor() throws IOException {
    LDConfig config = new LDConfig.Builder()
        .useLdd(true)
        .build();
    try (LDClient client = new LDClient("SDK_KEY", config)) {    
      assertEquals(UpdateProcessor.NullUpdateProcessor.class, client.updateProcessor.getClass());
    }
  }

  @Test
  public void lddModeClientHasDefaultEventProcessor() throws IOException {
    LDConfig config = new LDConfig.Builder()
        .useLdd(true)
        .build();
    try (LDClient client = new LDClient("SDK_KEY", config)) {    
      assertEquals(DefaultEventProcessor.class, client.eventProcessor.getClass());
    }
  }

  @Test
  public void lddModeClientIsInitialized() throws IOException {
    LDConfig config = new LDConfig.Builder()
        .useLdd(true)
        .build();
    try (LDClient client = new LDClient("SDK_KEY", config)) {
      assertTrue(client.initialized());
    }
  }
  
  @Test
  public void lddModeClientGetsFlagFromFeatureStore() throws IOException {
    FeatureStore testFeatureStore = initedFeatureStore();
    LDConfig config = new LDConfig.Builder()
        .useLdd(true)
        .featureStoreFactory(specificFeatureStore(testFeatureStore))
        .build();
    FeatureFlag flag = flagWithValue("key", LDValue.of(true));
    testFeatureStore.upsert(FEATURES, flag);
    try (LDClient client = new LDClient("SDK_KEY", config)) {
      assertTrue(client.boolVariation("key", new LDUser("user"), false));
    }
  }
}