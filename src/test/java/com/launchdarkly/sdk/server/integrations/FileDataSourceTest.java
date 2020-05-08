package com.launchdarkly.sdk.server.integrations;

import com.launchdarkly.sdk.server.LDConfig;
import com.launchdarkly.sdk.server.interfaces.DataSource;
import com.launchdarkly.sdk.server.interfaces.DataStore;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Future;

import static com.google.common.collect.Iterables.size;
import static com.launchdarkly.sdk.server.DataModel.FEATURES;
import static com.launchdarkly.sdk.server.DataModel.SEGMENTS;
import static com.launchdarkly.sdk.server.DataStoreTestTypes.toItemsMap;
import static com.launchdarkly.sdk.server.TestComponents.clientContext;
import static com.launchdarkly.sdk.server.TestComponents.dataSourceUpdates;
import static com.launchdarkly.sdk.server.TestComponents.inMemoryDataStore;
import static com.launchdarkly.sdk.server.integrations.FileDataSourceTestData.ALL_FLAG_KEYS;
import static com.launchdarkly.sdk.server.integrations.FileDataSourceTestData.ALL_SEGMENT_KEYS;
import static com.launchdarkly.sdk.server.integrations.FileDataSourceTestData.getResourceContents;
import static com.launchdarkly.sdk.server.integrations.FileDataSourceTestData.resourceFilePath;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.fail;

@SuppressWarnings("javadoc")
public class FileDataSourceTest {
  private static final Path badFilePath = Paths.get("no-such-file.json");
  
  private final DataStore store = inMemoryDataStore();
  private final LDConfig config = new LDConfig.Builder().build();
  private final FileDataSourceBuilder factory;
  
  public FileDataSourceTest() throws Exception {
    factory = makeFactoryWithFile(resourceFilePath("all-properties.json"));
  }
  
  private static FileDataSourceBuilder makeFactoryWithFile(Path path) {
    return FileData.dataSource().filePaths(path);
  }

  private DataSource makeDataSource(FileDataSourceBuilder builder) {
    return builder.createDataSource(clientContext("", config), dataSourceUpdates(store));
  }
  
  @Test
  public void flagsAreNotLoadedUntilStart() throws Exception {
    try (DataSource fp = makeDataSource(factory)) {
      assertThat(store.isInitialized(), equalTo(false));
      assertThat(size(store.getAll(FEATURES).getItems()), equalTo(0));
      assertThat(size(store.getAll(SEGMENTS).getItems()), equalTo(0));
    }
  }
  
  @Test
  public void flagsAreLoadedOnStart() throws Exception {
    try (DataSource fp = makeDataSource(factory)) {
      fp.start();
      assertThat(store.isInitialized(), equalTo(true));
      assertThat(toItemsMap(store.getAll(FEATURES)).keySet(), equalTo(ALL_FLAG_KEYS));
      assertThat(toItemsMap(store.getAll(SEGMENTS)).keySet(), equalTo(ALL_SEGMENT_KEYS));
    }
  }
  
  @Test
  public void startFutureIsCompletedAfterSuccessfulLoad() throws Exception {
    try (DataSource fp = makeDataSource(factory)) {
      Future<Void> future = fp.start();
      assertThat(future.isDone(), equalTo(true));
    }
  }
  
  @Test
  public void initializedIsTrueAfterSuccessfulLoad() throws Exception {
    try (DataSource fp = makeDataSource(factory)) {
      fp.start();
      assertThat(fp.isInitialized(), equalTo(true));
    }
  }
  
  @Test
  public void startFutureIsCompletedAfterUnsuccessfulLoad() throws Exception {
    factory.filePaths(badFilePath);
    try (DataSource fp = makeDataSource(factory)) {
      Future<Void> future = fp.start();
      assertThat(future.isDone(), equalTo(true));
    }
  }
  
  @Test
  public void initializedIsFalseAfterUnsuccessfulLoad() throws Exception {
    factory.filePaths(badFilePath);
    try (DataSource fp = makeDataSource(factory)) {
      fp.start();
      assertThat(fp.isInitialized(), equalTo(false));
    }
  }
  
  @Test
  public void modifiedFileIsNotReloadedIfAutoUpdateIsOff() throws Exception {
    File file = makeTempFlagFile();
    FileDataSourceBuilder factory1 = makeFactoryWithFile(file.toPath());
    try {
      setFileContents(file, getResourceContents("flag-only.json"));
      try (DataSource fp = makeDataSource(factory1)) {
        fp.start();
        setFileContents(file, getResourceContents("segment-only.json"));
        Thread.sleep(400);
        assertThat(toItemsMap(store.getAll(FEATURES)).size(), equalTo(1));
        assertThat(toItemsMap(store.getAll(SEGMENTS)).size(), equalTo(0));
      }
    } finally {
      file.delete();
    }
  }

  // Note that the auto-update tests may fail when run on a Mac, but succeed on Ubuntu. This is because on
  // MacOS there is no native implementation of WatchService, and the default implementation is known
  // to be extremely slow. See: https://stackoverflow.com/questions/9588737/is-java-7-watchservice-slow-for-anyone-else
  @Test
  public void modifiedFileIsReloadedIfAutoUpdateIsOn() throws Exception {
    File file = makeTempFlagFile();
    FileDataSourceBuilder factory1 = makeFactoryWithFile(file.toPath()).autoUpdate(true);
    long maxMsToWait = 10000;
    try {
      setFileContents(file, getResourceContents("flag-only.json"));  // this file has 1 flag
      try (DataSource fp = makeDataSource(factory1)) {
        fp.start();
        Thread.sleep(1000);
        setFileContents(file, getResourceContents("all-properties.json"));  // this file has all the flags
        long deadline = System.currentTimeMillis() + maxMsToWait;
        while (System.currentTimeMillis() < deadline) {
          if (toItemsMap(store.getAll(FEATURES)).size() == ALL_FLAG_KEYS.size()) {
            // success
            return;
          }
          Thread.sleep(500);
        }
        fail("Waited " + maxMsToWait + "ms after modifying file and it did not reload");
      }
    } finally {
      file.delete();
    }
  }
  
  @Test
  public void ifFilesAreBadAtStartTimeAutoUpdateCanStillLoadGoodDataLater() throws Exception {
    File file = makeTempFlagFile();
    setFileContents(file, "not valid");
    FileDataSourceBuilder factory1 = makeFactoryWithFile(file.toPath()).autoUpdate(true);
    long maxMsToWait = 10000;
    try {
      try (DataSource fp = makeDataSource(factory1)) {
        fp.start();
        Thread.sleep(1000);
        setFileContents(file, getResourceContents("flag-only.json"));  // this file has 1 flag
        long deadline = System.currentTimeMillis() + maxMsToWait;
        while (System.currentTimeMillis() < deadline) {
          if (toItemsMap(store.getAll(FEATURES)).size() > 0) {
            // success
            return;
          }
          Thread.sleep(500);
        }
        fail("Waited " + maxMsToWait + "ms after modifying file and it did not reload");
      }
    } finally {
      file.delete();
    }
  }
  
  private File makeTempFlagFile() throws Exception {
    return File.createTempFile("flags", ".json");
  }
  
  private void setFileContents(File file, String content) throws Exception {
    Files.write(file.toPath(), content.getBytes("UTF-8"));
  }
}
