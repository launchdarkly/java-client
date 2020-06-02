package com.launchdarkly.sdk.server.integrations;

import com.launchdarkly.sdk.server.LDConfig;
import com.launchdarkly.sdk.server.TestComponents;
import com.launchdarkly.sdk.server.TestComponents.MockDataSourceUpdates;
import com.launchdarkly.sdk.server.integrations.FileDataSourceTestData.TempDir;
import com.launchdarkly.sdk.server.integrations.FileDataSourceTestData.TempFile;
import com.launchdarkly.sdk.server.interfaces.DataSource;
import com.launchdarkly.sdk.server.interfaces.DataSourceStatusProvider;
import com.launchdarkly.sdk.server.interfaces.DataStore;

import org.junit.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static com.launchdarkly.sdk.server.DataModel.FEATURES;
import static com.launchdarkly.sdk.server.DataModel.SEGMENTS;
import static com.launchdarkly.sdk.server.DataStoreTestTypes.toItemsMap;
import static com.launchdarkly.sdk.server.TestComponents.clientContext;
import static com.launchdarkly.sdk.server.TestComponents.inMemoryDataStore;
import static com.launchdarkly.sdk.server.TestUtil.repeatWithTimeout;
import static com.launchdarkly.sdk.server.TestUtil.requireDataSourceStatusEventually;
import static com.launchdarkly.sdk.server.integrations.FileDataSourceTestData.ALL_FLAG_KEYS;
import static com.launchdarkly.sdk.server.integrations.FileDataSourceTestData.getResourceContents;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

@SuppressWarnings("javadoc")
public class FileDataSourceAutoUpdateTest {
  private final DataStore store;
  private MockDataSourceUpdates dataSourceUpdates;
  private final LDConfig config = new LDConfig.Builder().build();
  
  public FileDataSourceAutoUpdateTest() throws Exception {
    store = inMemoryDataStore();
    dataSourceUpdates = TestComponents.dataSourceUpdates(store);
  }
  
  private static FileDataSourceBuilder makeFactoryWithFile(Path path) {
    return FileData.dataSource().filePaths(path);
  }

  private DataSource makeDataSource(FileDataSourceBuilder builder) {
    return builder.createDataSource(clientContext("", config), dataSourceUpdates);
  }
  
  @Test
  public void modifiedFileIsNotReloadedIfAutoUpdateIsOff() throws Exception {
    try (TempDir dir = TempDir.create()) {
      try (TempFile file = dir.tempFile(".json")) {
        file.setContents(getResourceContents("flag-only.json"));
        FileDataSourceBuilder factory1 = makeFactoryWithFile(file.path);
        try (DataSource fp = makeDataSource(factory1)) {
          fp.start();
          file.setContents(getResourceContents("segment-only.json"));
          Thread.sleep(400);
          assertThat(toItemsMap(store.getAll(FEATURES)).size(), equalTo(1));
          assertThat(toItemsMap(store.getAll(SEGMENTS)).size(), equalTo(0));
        } 
      }
    }
  }

  // Note that the auto-update tests may fail when run on a Mac, but succeed on Ubuntu. This is because on
  // MacOS there is no native implementation of WatchService, and the default implementation is known
  // to be extremely slow. See: https://stackoverflow.com/questions/9588737/is-java-7-watchservice-slow-for-anyone-else
  @Test
  public void modifiedFileIsReloadedIfAutoUpdateIsOn() throws Exception {
    try (TempDir dir = TempDir.create()) {
      try (TempFile file = dir.tempFile(".json")) {
        FileDataSourceBuilder factory1 = makeFactoryWithFile(file.path).autoUpdate(true);
        file.setContents(getResourceContents("flag-only.json"));  // this file has 1 flag
        try (DataSource fp = makeDataSource(factory1)) {
          fp.start();
          Thread.sleep(1000);
          file.setContents(getResourceContents("all-properties.json"));  // this file has all the flags
          repeatWithTimeout(Duration.ofSeconds(10), Duration.ofMillis(500), () -> {
            if (toItemsMap(store.getAll(FEATURES)).size() == ALL_FLAG_KEYS.size()) {
              // success - return a non-null value to make repeatWithTimeout end
              return fp;
            }
            return null;
          });
        }
      }
    }
  }
  
  @Test
  public void ifFilesAreBadAtStartTimeAutoUpdateCanStillLoadGoodDataLater() throws Exception {
    BlockingQueue<DataSourceStatusProvider.Status> statuses = new LinkedBlockingQueue<>();
    dataSourceUpdates.register(statuses::add);
    
    try (TempDir dir = TempDir.create()) {
      try (TempFile file = dir.tempFile(".json")) {
        file.setContents("not valid");
        FileDataSourceBuilder factory1 = makeFactoryWithFile(file.path).autoUpdate(true);
        try (DataSource fp = makeDataSource(factory1)) {
          fp.start();
          Thread.sleep(1000);
          file.setContents(getResourceContents("flag-only.json"));  // this file has 1 flag
          repeatWithTimeout(Duration.ofSeconds(10), Duration.ofMillis(500), () -> {
            if (toItemsMap(store.getAll(FEATURES)).size() > 0) {
              // success - status is now VALID, after having first been INITIALIZING - can still see that an error occurred
              DataSourceStatusProvider.Status status = requireDataSourceStatusEventually(statuses,
                  DataSourceStatusProvider.State.VALID, DataSourceStatusProvider.State.INITIALIZING);
              assertNotNull(status.getLastError());
              assertEquals(DataSourceStatusProvider.ErrorKind.INVALID_DATA, status.getLastError().getKind());
  
              return status;
            }
            return null;
          });
        }
      }
    }
  }
}
