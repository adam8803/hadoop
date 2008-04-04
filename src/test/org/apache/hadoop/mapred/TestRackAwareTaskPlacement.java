/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.mapred;

import java.io.IOException;

import junit.framework.TestCase;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.dfs.*;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.BlockLocation;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.SequenceFile.CompressionType;
import org.apache.hadoop.mapred.SortValidator.RecordStatsChecker.NonSplitableSequenceFileInputFormat;
import org.apache.hadoop.mapred.lib.IdentityMapper;
import org.apache.hadoop.mapred.lib.IdentityReducer;

public class TestRackAwareTaskPlacement extends TestCase {
  private static final String rack1[] = new String[] {
    "/r1"
  };
  private static final String hosts1[] = new String[] {
    "host1.rack1.com"
  };
  private static final String rack2[] = new String[] {
    "/r2", "/r2"
  };
  private static final String hosts2[] = new String[] {
    "host1.rack2.com", "host2.rack2.com"
  };
  private static final String hosts3[] = new String[] {
    "host3.rack1.com"
  };
  private static final String hosts4[] = new String[] {
    "host1.rack2.com"
  };
  final Path inDir = new Path("/racktesting");
  final Path outputPath = new Path("/output");
  
  public void testTaskPlacement() throws IOException {
    String namenode = null;
    MiniDFSCluster dfs = null;
    MiniMRCluster mr = null;
    FileSystem fileSys = null;
    try {
      final int taskTrackers = 1;

      /* Start 3 datanodes, one in rack r1, and two in r2. Create three
       * files (splits).
       * 1) file1, just after starting the datanode on r1, with 
       *    a repl factor of 1, and,
       * 2) file2 & file3 after starting the other two datanodes, with a repl 
       *    factor of 3.
       * At the end, file1 will be present on only datanode1, and, file2 and 
       * file3, will be present on all datanodes. 
       */
      Configuration conf = new Configuration();
      conf.setBoolean("dfs.replication.considerLoad", false);
      dfs = new MiniDFSCluster(conf, 1, true, rack1, hosts1);
      dfs.waitActive();
      fileSys = dfs.getFileSystem();
      if (!fileSys.mkdirs(inDir)) {
        throw new IOException("Mkdirs failed to create " + inDir.toString());
      }
      writeFile(dfs.getNameNode(), conf, new Path(inDir + "/file1"), (short)1);
      dfs.startDataNodes(conf, 2, true, null, rack2, hosts2, null);
      dfs.waitActive();

      writeFile(dfs.getNameNode(), conf, new Path(inDir + "/file2"), (short)3);
      writeFile(dfs.getNameNode(), conf, new Path(inDir + "/file3"), (short)3);
      
      namenode = (dfs.getFileSystem()).getUri().getHost() + ":" + 
                 (dfs.getFileSystem()).getUri().getPort(); 
      /* Run a job with the (only)tasktracker on rack2. The rack location
       * of the tasktracker will determine how many data/rack local maps it
       * runs. The hostname of the tasktracker is set to same as one of the 
       * datanodes.
       */
      mr = new MiniMRCluster(taskTrackers, namenode, 1, rack2, hosts4);
      JobConf jobConf = mr.createJobConf();
      if (fileSys.exists(outputPath)) {
        fileSys.delete(outputPath, true);
      }
      /* The job is configured with three maps since there are three 
       * (non-splittable) files. On rack2, there are two files and both
       * have repl of three. The blocks for those files must therefore be
       * present on all the datanodes, in particular, the datanodes on rack2.
       * The third input file is pulled from rack1.
       */
      RunningJob job = launchJob(jobConf, 3);
      Counters counters = job.getCounters();
      assertEquals("Number of Data-local maps", 
          counters.getCounter(JobInProgress.Counter.DATA_LOCAL_MAPS), 2);
      assertEquals("Number of Rack-local maps", 
          counters.getCounter(JobInProgress.Counter.RACK_LOCAL_MAPS), 0);
      mr.waitUntilIdle();
      
      /* Run a job with the (only)tasktracker on rack1.
       */
      mr = new MiniMRCluster(taskTrackers, namenode, 1, rack1, hosts3);
      jobConf = mr.createJobConf();
      fileSys = dfs.getFileSystem();
      if (fileSys.exists(outputPath)) {
        fileSys.delete(outputPath, true);
      }
      /* The job is configured with three maps since there are three 
       * (non-splittable) files. On rack1, because of the way in which repl
       * was setup while creating the files, we will have all the three files. 
       * Thus, a tasktracker will find all inputs in this rack.
       */
      job = launchJob(jobConf, 3);
      counters = job.getCounters();
      assertEquals("Number of Rack-local maps",
          counters.getCounter(JobInProgress.Counter.RACK_LOCAL_MAPS), 3);
      mr.waitUntilIdle();
      
    } finally {
      if (dfs != null) { 
        dfs.shutdown(); 
      }
      if (mr != null) { 
        mr.shutdown();
      }
    }
  }
  private void writeFile(NameNode namenode, Configuration conf, Path name, 
      short replication) throws IOException {
    FileSystem fileSys = FileSystem.get(conf);
    SequenceFile.Writer writer = 
      SequenceFile.createWriter(fileSys, conf, name, 
                                BytesWritable.class, BytesWritable.class,
                                CompressionType.NONE);
    writer.append(new BytesWritable(), new BytesWritable());
    writer.close();
    fileSys.setReplication(name, replication);
    waitForReplication(fileSys, namenode, name, replication);
  }
  private void waitForReplication(FileSystem fileSys, NameNode namenode, 
      Path name, short replication) throws IOException {
    //wait for the replication to happen
    boolean isReplicationDone;
    
    do {
      BlockLocation[] hints = fileSys.getFileBlockLocations(name, 0, 
                                                            Long.MAX_VALUE);
      if (hints[0].getHosts().length == replication) {
        isReplicationDone = true;
      } else {
        isReplicationDone = false;  
      }
      try {
        Thread.sleep(1000);
      } catch (InterruptedException ie) {
        return;
      }
    } while(!isReplicationDone);
  }
  private RunningJob launchJob(JobConf jobConf, int numMaps) throws IOException {
    jobConf.setJobName("TestForRackAwareness");
    jobConf.setInputFormat(NonSplitableSequenceFileInputFormat.class);
    jobConf.setOutputFormat(SequenceFileOutputFormat.class);
    jobConf.setInputPath(inDir);
    FileOutputFormat.setOutputPath(jobConf, outputPath);
    jobConf.setMapperClass(IdentityMapper.class);
    jobConf.setReducerClass(IdentityReducer.class);
    jobConf.setOutputKeyClass(BytesWritable.class);
    jobConf.setOutputValueClass(BytesWritable.class);
    jobConf.setNumMapTasks(numMaps);
    jobConf.setNumReduceTasks(0);
    jobConf.setJar("build/test/testjar/testjob.jar");
    return JobClient.runJob(jobConf);
  }
}
