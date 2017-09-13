/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.ahat;

import com.android.ahat.heapdump.AhatClassObj;
import com.android.ahat.heapdump.AhatInstance;
import com.android.ahat.heapdump.AhatSnapshot;
import com.android.ahat.heapdump.Diff;
import com.android.ahat.heapdump.FieldValue;
import com.android.ahat.heapdump.Site;
import com.android.ahat.heapdump.Value;
import com.android.tools.perflib.captures.DataBuffer;
import com.android.tools.perflib.heap.ProguardMap;
import com.android.tools.perflib.heap.io.InMemoryBuffer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * The TestDump class is used to get the current and baseline AhatSnapshots
 * for heap dumps generated by the test-dump program that are stored as
 * resources in this jar file.
 */
public class TestDump {
  // It can take on the order of a second to parse and process test dumps.
  // To avoid repeating this overhead for each test case, we provide a way to
  // cache loaded instance of TestDump and reuse it when possible. In theory
  // the test cases should not be able to modify the cached snapshot in a way
  // that is visible to other test cases.
  private static List<TestDump> mCachedTestDumps = new ArrayList<TestDump>();

  // The name of the resources this test dump is loaded from.
  private String mHprofResource;
  private String mHprofBaseResource;
  private String mMapResource;

  // If the test dump fails to load the first time, it will likely fail every
  // other test we try. Rather than having to wait a potentially very long
  // time for test dump loading to fail over and over again, record when it
  // fails and don't try to load it again.
  private boolean mTestDumpFailed = true;

  // The loaded heap dumps.
  private AhatSnapshot mSnapshot;
  private AhatSnapshot mBaseline;

  // Cached reference to the 'Main' class object in the snapshot and baseline
  // heap dumps.
  private AhatClassObj mMain;
  private AhatClassObj mBaselineMain;

  /**
   * Read the named resource into a DataBuffer.
   */
  private static DataBuffer dataBufferFromResource(String name) throws IOException {
    ClassLoader loader = TestDump.class.getClassLoader();
    InputStream is = loader.getResourceAsStream(name);
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    byte[] buf = new byte[4096];
    int read;
    while ((read = is.read(buf)) != -1) {
      baos.write(buf, 0, read);
    }
    return new InMemoryBuffer(baos.toByteArray());
  }

  /**
   * Create a TestDump instance.
   * The load() method should be called to load and process the heap dumps.
   * The files are specified as names of resources compiled into the jar file.
   * The baseline resouce may be null to indicate that no diffing should be
   * performed.
   * The map resource may be null to indicate no proguard map will be used.
   *
   */
  private TestDump(String hprofResource, String hprofBaseResource, String mapResource) {
    mHprofResource = hprofResource;
    mHprofBaseResource = hprofBaseResource;
    mMapResource = mapResource;
  }

  /**
   * Load the heap dumps for this TestDump.
   * An IOException is thrown if there is a failure reading the hprof files or
   * the proguard map.
   */
  private void load() throws IOException {
    ProguardMap map = new ProguardMap();
    if (mMapResource != null) {
      try {
        ClassLoader loader = TestDump.class.getClassLoader();
        InputStream is = loader.getResourceAsStream(mMapResource);
        map.readFromReader(new InputStreamReader(is));
      } catch (ParseException e) {
        throw new IOException("Unable to load proguard map", e);
      }
    }

    DataBuffer hprof = dataBufferFromResource(mHprofResource);
    mSnapshot = AhatSnapshot.fromDataBuffer(hprof, map);
    mMain = findClass(mSnapshot, "Main");
    assert(mMain != null);

    if (mHprofBaseResource != null) {
      DataBuffer hprofBase = dataBufferFromResource(mHprofBaseResource);
      mBaseline = AhatSnapshot.fromDataBuffer(hprofBase, map);
      mBaselineMain = findClass(mBaseline, "Main");
      assert(mBaselineMain != null);

      Diff.snapshots(mSnapshot, mBaseline);
    }

    mTestDumpFailed = false;
  }

  /**
   * Get the AhatSnapshot for the test dump program.
   */
  public AhatSnapshot getAhatSnapshot() {
    return mSnapshot;
  }

  /**
   * Get the baseline AhatSnapshot for the test dump program.
   */
  public AhatSnapshot getBaselineAhatSnapshot() {
    return mBaseline;
  }

  /**
   * Returns the value of a field in the DumpedStuff instance in the
   * snapshot for the test-dump program.
   */
  public Value getDumpedValue(String name) {
    return getDumpedValue(name, mMain);
  }

  /**
   * Returns the value of a field in the DumpedStuff instance in the
   * baseline snapshot for the test-dump program.
   */
  public Value getBaselineDumpedValue(String name) {
    return getDumpedValue(name, mBaselineMain);
  }

  /**
   * Returns the value of a field in the DumpedStuff instance given the Main
   * class object for the snapshot.
   */
  private static Value getDumpedValue(String name, AhatClassObj main) {
    AhatInstance stuff = null;
    for (FieldValue field : main.getStaticFieldValues()) {
      if ("stuff".equals(field.name)) {
        stuff = field.value.asAhatInstance();
      }
    }
    return stuff.getField(name);
  }

  /**
   * Returns a class object in the given heap dump whose name matches the
   * given name, or null if no such class object could be found.
   */
  private static AhatClassObj findClass(AhatSnapshot snapshot, String name) {
    Site root = snapshot.getRootSite();
    Collection<AhatInstance> classes = new ArrayList<AhatInstance>();
    root.getObjects(null, "java.lang.Class", classes);
    for (AhatInstance inst : classes) {
      if (inst.isClassObj()) {
        AhatClassObj cls = inst.asClassObj();
        if (name.equals(cls.getName())) {
          return cls;
        }
      }
    }
    return null;
  }

  /**
   * Returns a class object in the heap dump whose name matches the given
   * name, or null if no such class object could be found.
   */
  public AhatClassObj findClass(String name) {
    return findClass(mSnapshot, name);
  }

  /**
   * Returns the value of a non-primitive field in the DumpedStuff instance in
   * the snapshot for the test-dump program.
   */
  public AhatInstance getDumpedAhatInstance(String name) {
    Value value = getDumpedValue(name);
    return value == null ? null : value.asAhatInstance();
  }

  /**
   * Returns the value of a non-primitive field in the DumpedStuff instance in
   * the baseline snapshot for the test-dump program.
   */
  public AhatInstance getBaselineDumpedAhatInstance(String name) {
    Value value = getBaselineDumpedValue(name);
    return value == null ? null : value.asAhatInstance();
  }

  /**
   * Get the default (cached) test dump.
   * An IOException is thrown if there is an error reading the test dump hprof
   * file.
   * To improve performance, this returns a cached instance of the TestDump
   * when possible.
   */
  public static synchronized TestDump getTestDump() throws IOException {
    return getTestDump("test-dump.hprof", "test-dump-base.hprof", "test-dump.map");
  }

  /**
   * Get a (cached) test dump.
   * @param hprof - The string resouce name of the hprof file.
   * @param base - The string resouce name of the baseline hprof, may be null.
   * @param map - The string resouce name of the proguard map, may be null.
   * An IOException is thrown if there is an error reading the test dump hprof
   * file.
   * To improve performance, this returns a cached instance of the TestDump
   * when possible.
   */
  public static synchronized TestDump getTestDump(String hprof, String base, String map)
    throws IOException {
    for (TestDump loaded : mCachedTestDumps) {
      if (Objects.equals(loaded.mHprofResource, hprof)
          && Objects.equals(loaded.mHprofBaseResource, base)
          && Objects.equals(loaded.mMapResource, map)) {
        if (loaded.mTestDumpFailed) {
          throw new IOException("Test dump failed before, assuming it will again");
        }
        return loaded;
      }
    }

    TestDump dump = new TestDump(hprof, base, map);
    mCachedTestDumps.add(dump);
    dump.load();
    return dump;
  }
}
