package com.launchdarkly.client;

import com.launchdarkly.client.interfaces.VersionedData;
import com.launchdarkly.client.interfaces.VersionedDataKind;
import com.launchdarkly.client.value.LDValue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.launchdarkly.client.DataModel.DataKinds.FEATURES;

/**
 * A decorated {@link InMemoryDataStore} which provides functionality to create (or override) true or false feature flags for all users.
 * <p>
 * Using this store is useful for testing purposes when you want to have runtime support for turning specific features on or off.
 *
 * @deprecated Will be replaced by a file-based test fixture.
 */
@Deprecated
public class TestDataStore extends InMemoryDataStore {
  static List<LDValue> TRUE_FALSE_VARIATIONS = Arrays.asList(LDValue.of(true), LDValue.of(false));

  private AtomicInteger version = new AtomicInteger(0);
  private volatile boolean initializedForTests = false;
  
  /**
   * Sets the value of a boolean feature flag for all users.
   *
   * @param key the key of the feature flag
   * @param value the new value of the feature flag
   * @return the feature flag
   */
  public DataModel.FeatureFlag setBooleanValue(String key, boolean value) {
    return setJsonValue(key, LDValue.of(value));
  }

  /**
   * Turns a feature, identified by key, to evaluate to true for every user. If the feature rules already exist in the store then it will override it to be true for every {@link LDUser}.
   * If the feature rule is not currently in the store, it will create one that is true for every {@link LDUser}.
   *
   * @param key the key of the feature flag to evaluate to true
   * @return the feature flag
   */
  public DataModel.FeatureFlag setFeatureTrue(String key) {
    return setBooleanValue(key, true);
  }
  
  /**
   * Turns a feature, identified by key, to evaluate to false for every user. If the feature rules already exist in the store then it will override it to be false for every {@link LDUser}.
   * If the feature rule is not currently in the store, it will create one that is false for every {@link LDUser}.
   *
   * @param key the key of the feature flag to evaluate to false
   * @return the feature flag
   */
  public DataModel.FeatureFlag setFeatureFalse(String key) {
    return setBooleanValue(key, false);
  }
  
  /**
   * Sets the value of an integer multivariate feature flag, for all users.
   * @param key the key of the flag
   * @param value the new value of the flag
   * @return the feature flag
     */
  public DataModel.FeatureFlag setIntegerValue(String key, int value) {
    return setJsonValue(key, LDValue.of(value));
  }

  /**
   * Sets the value of a double multivariate feature flag, for all users.
   * @param key the key of the flag
   * @param value the new value of the flag
   * @return the feature flag
     */
  public DataModel.FeatureFlag setDoubleValue(String key, double value) {
    return setJsonValue(key, LDValue.of(value));
  }

  /**
   * Sets the value of a string multivariate feature flag, for all users.
   * @param key the key of the flag
   * @param value the new value of the flag
   * @return the feature flag
     */
  public DataModel.FeatureFlag setStringValue(String key, String value) {
    return setJsonValue(key, LDValue.of(value));
  }

  /**
   * Sets the value of a multivariate feature flag, for all users.
   * @param key the key of the flag
   * @param value the new value of the flag
   * @return the feature flag
     */
  public DataModel.FeatureFlag setJsonValue(String key, LDValue value) {
    DataModel.FeatureFlag newFeature = new DataModel.FeatureFlag(key,
        version.incrementAndGet(),
        false,
        null,
        null,
        null,
        null,
        null,
        0,
        Arrays.asList(value),
        false,
        false,
        false,
        null,
        false);
    upsert(FEATURES, newFeature);
    return newFeature;
  }
  
  @Override
  public void init(Map<VersionedDataKind<?>, Map<String, ? extends VersionedData>> allData) {
    super.init(allData);
    initializedForTests = true;
  }
  
  @Override
  public boolean initialized() {
    return initializedForTests;
  }
  
  /**
   * Sets the initialization status that the data store will report to the SDK 
   * @param value true if the store should show as initialized
   */
  public void setInitialized(boolean value) {
    initializedForTests = value;
  }
}
