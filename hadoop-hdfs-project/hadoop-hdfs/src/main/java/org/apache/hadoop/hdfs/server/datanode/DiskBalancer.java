/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package org.apache.hadoop.hdfs.server.datanode;

import com.google.common.base.Preconditions;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.protocol.ExtendedBlock;
import org.apache.hadoop.hdfs.server.datanode.DiskBalancerWorkStatus
    .DiskBalancerWorkEntry;
import org.apache.hadoop.hdfs.server.datanode.DiskBalancerWorkStatus.Result;
import org.apache.hadoop.hdfs.server.datanode.fsdataset.FsDatasetSpi;
import org.apache.hadoop.hdfs.server.datanode.fsdataset.FsVolumeSpi;
import org.apache.hadoop.hdfs.server.diskbalancer.DiskBalancerConstants;
import org.apache.hadoop.hdfs.server.diskbalancer.DiskBalancerException;
import org.apache.hadoop.hdfs.server.diskbalancer.planner.NodePlan;
import org.apache.hadoop.hdfs.server.diskbalancer.planner.Step;
import org.apache.hadoop.util.Time;
import org.codehaus.jackson.map.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Worker class for Disk Balancer.
 * <p>
 * Here is the high level logic executed by this class. Users can submit disk
 * balancing plans using submitPlan calls. After a set of sanity checks the plan
 * is admitted and put into workMap.
 * <p>
 * The executePlan launches a thread that picks up work from workMap and hands
 * it over to the BlockMover#copyBlocks function.
 * <p>
 * Constraints :
 * <p>
 * Only one plan can be executing in a datanode at any given time. This is
 * ensured by checking the future handle of the worker thread in submitPlan.
 */
@InterfaceAudience.Private
public class DiskBalancer {

  private static final Logger LOG = LoggerFactory.getLogger(DiskBalancer
      .class);
  private final FsDatasetSpi<?> dataset;
  private final String dataNodeUUID;
  private final BlockMover blockMover;
  private final ReentrantLock lock;
  private final ConcurrentHashMap<VolumePair, DiskBalancerWorkItem> workMap;
  private boolean isDiskBalancerEnabled = false;
  private ExecutorService scheduler;
  private Future future;
  private String planID;
  private DiskBalancerWorkStatus.Result currentResult;
  private long bandwidth;

  /**
   * Constructs a Disk Balancer object. This object takes care of reading a
   * NodePlan and executing it against a set of volumes.
   *
   * @param dataNodeUUID - Data node UUID
   * @param conf         - Hdfs Config
   * @param blockMover   - Object that supports moving blocks.
   */
  public DiskBalancer(String dataNodeUUID,
                      Configuration conf, BlockMover blockMover) {
    this.currentResult = Result.NO_PLAN;
    this.blockMover = blockMover;
    this.dataset = this.blockMover.getDataset();
    this.dataNodeUUID = dataNodeUUID;
    scheduler = Executors.newSingleThreadExecutor();
    lock = new ReentrantLock();
    workMap = new ConcurrentHashMap<>();
    this.planID = "";  // to keep protobuf happy.
    this.isDiskBalancerEnabled = conf.getBoolean(
        DFSConfigKeys.DFS_DISK_BALANCER_ENABLED,
        DFSConfigKeys.DFS_DISK_BALANCER_ENABLED_DEFAULT);
    this.bandwidth = conf.getInt(
        DFSConfigKeys.DFS_DISK_BALANCER_MAX_DISK_THROUGHPUT,
        DFSConfigKeys.DFS_DISK_BALANCER_MAX_DISK_THROUGHPUT_DEFAULT);
  }

  /**
   * Shutdown  disk balancer services.
   */
  public void shutdown() {
    lock.lock();
    try {
      this.isDiskBalancerEnabled = false;
      this.currentResult = Result.NO_PLAN;
      if ((this.future != null) && (!this.future.isDone())) {
        this.currentResult = Result.PLAN_CANCELLED;
        this.blockMover.setExitFlag();
        shutdownExecutor();
      }
    } finally {
      lock.unlock();
    }
  }

  /**
   * Shutdown the executor.
   */
  private void shutdownExecutor() {
    final int secondsTowait = 10;
    scheduler.shutdown();
    try {
      if (!scheduler.awaitTermination(secondsTowait, TimeUnit.SECONDS)) {
        scheduler.shutdownNow();
        if (!scheduler.awaitTermination(secondsTowait, TimeUnit.SECONDS)) {
          LOG.error("Disk Balancer : Scheduler did not terminate.");
        }
      }
    } catch (InterruptedException ex) {
      scheduler.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Takes a client submitted plan and converts into a set of work items that
   * can be executed by the blockMover.
   *
   * @param planID      - A SHA512 of the plan string
   * @param planVersion - version of the plan string - for future use.
   * @param plan        - Actual Plan
   * @param force       - Skip some validations and execute the plan file.
   * @throws DiskBalancerException
   */
  public void submitPlan(String planID, long planVersion, String plan,
                         boolean force) throws DiskBalancerException {

    lock.lock();
    try {
      checkDiskBalancerEnabled();
      if ((this.future != null) && (!this.future.isDone())) {
        LOG.error("Disk Balancer - Executing another plan, submitPlan failed.");
        throw new DiskBalancerException("Executing another plan",
            DiskBalancerException.Result.PLAN_ALREADY_IN_PROGRESS);
      }
      NodePlan nodePlan = verifyPlan(planID, planVersion, plan, force);
      createWorkPlan(nodePlan);
      this.planID = planID;
      this.currentResult = Result.PLAN_UNDER_PROGRESS;
      executePlan();
    } finally {
      lock.unlock();
    }
  }

  /**
   * Returns the Current Work Status of a submitted Plan.
   *
   * @return DiskBalancerWorkStatus.
   * @throws DiskBalancerException
   */
  public DiskBalancerWorkStatus queryWorkStatus() throws DiskBalancerException {
    lock.lock();
    try {
      checkDiskBalancerEnabled();
      // if we had a plan in progress, check if it is finished.
      if (this.currentResult == Result.PLAN_UNDER_PROGRESS &&
          this.future != null &&
          this.future.isDone()) {
        this.currentResult = Result.PLAN_DONE;
      }

      DiskBalancerWorkStatus status =
          new DiskBalancerWorkStatus(this.currentResult, this.planID);
      for (Map.Entry<VolumePair, DiskBalancerWorkItem> entry :
          workMap.entrySet()) {
        DiskBalancerWorkEntry workEntry = new DiskBalancerWorkEntry(
            entry.getKey().getSource().getBasePath(),
            entry.getKey().getDest().getBasePath(),
            entry.getValue());
        status.addWorkEntry(workEntry);
      }
      return status;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Cancels a running plan.
   *
   * @param planID - Hash of the plan to cancel.
   * @throws DiskBalancerException
   */
  public void cancelPlan(String planID) throws DiskBalancerException {
    lock.lock();
    try {
      checkDiskBalancerEnabled();
      if (this.planID == null ||
          !this.planID.equals(planID) ||
          this.planID.isEmpty()) {
        LOG.error("Disk Balancer - No such plan. Cancel plan failed. PlanID: " +
            planID);
        throw new DiskBalancerException("No such plan.",
            DiskBalancerException.Result.NO_SUCH_PLAN);
      }
      if (!this.future.isDone()) {
        this.blockMover.setExitFlag();
        shutdownExecutor();
        this.currentResult = Result.PLAN_CANCELLED;
      }
    } finally {
      lock.unlock();
    }
  }

  /**
   * Returns a volume ID to Volume base path map.
   *
   * @return Json string of the volume map.
   * @throws DiskBalancerException
   */
  public String getVolumeNames() throws DiskBalancerException {
    lock.lock();
    try {
      checkDiskBalancerEnabled();
      Map<String, String> pathMap = new HashMap<>();
      Map<String, FsVolumeSpi> volMap = getStorageIDToVolumeMap();
      for (Map.Entry<String, FsVolumeSpi> entry : volMap.entrySet()) {
        pathMap.put(entry.getKey(), entry.getValue().getBasePath());
      }
      ObjectMapper mapper = new ObjectMapper();
      return mapper.writeValueAsString(pathMap);
    } catch (DiskBalancerException ex) {
      throw ex;
    } catch (IOException e) {
      throw new DiskBalancerException("Internal error, Unable to " +
          "create JSON string.", e,
          DiskBalancerException.Result.INTERNAL_ERROR);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Returns the current bandwidth.
   *
   * @return string representation of bandwidth.
   * @throws DiskBalancerException
   */
  public long getBandwidth() throws DiskBalancerException {
    lock.lock();
    try {
      checkDiskBalancerEnabled();
      return this.bandwidth;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Throws if Disk balancer is disabled.
   *
   * @throws DiskBalancerException
   */
  private void checkDiskBalancerEnabled()
      throws DiskBalancerException {
    if (!isDiskBalancerEnabled) {
      throw new DiskBalancerException("Disk Balancer is not enabled.",
          DiskBalancerException.Result.DISK_BALANCER_NOT_ENABLED);
    }
  }

  /**
   * Verifies that user provided plan is valid.
   *
   * @param planID      - SHA 512 of the plan.
   * @param planVersion - Version of the plan, for future use.
   * @param plan        - Plan String in Json.
   * @param force       - Skip verifying when the plan was generated.
   * @return a NodePlan Object.
   * @throws DiskBalancerException
   */
  private NodePlan verifyPlan(String planID, long planVersion, String plan,
                              boolean force) throws DiskBalancerException {

    Preconditions.checkState(lock.isHeldByCurrentThread());
    verifyPlanVersion(planVersion);
    NodePlan nodePlan = verifyPlanHash(planID, plan);
    if (!force) {
      verifyTimeStamp(nodePlan);
    }
    verifyNodeUUID(nodePlan);
    return nodePlan;
  }

  /**
   * Verifies the plan version is something that we support.
   *
   * @param planVersion - Long version.
   * @throws DiskBalancerException
   */
  private void verifyPlanVersion(long planVersion)
      throws DiskBalancerException {
    if ((planVersion < DiskBalancerConstants.DISKBALANCER_MIN_VERSION) ||
        (planVersion > DiskBalancerConstants.DISKBALANCER_MAX_VERSION)) {
      LOG.error("Disk Balancer - Invalid plan version.");
      throw new DiskBalancerException("Invalid plan version.",
          DiskBalancerException.Result.INVALID_PLAN_VERSION);
    }
  }

  /**
   * Verifies that plan matches the SHA512 provided by the client.
   *
   * @param planID - Sha512 Hex Bytes
   * @param plan   - Plan String
   * @throws DiskBalancerException
   */
  private NodePlan verifyPlanHash(String planID, String plan)
      throws DiskBalancerException {
    final long sha512Length = 128;
    if (plan == null || plan.length() == 0) {
      LOG.error("Disk Balancer -  Invalid plan.");
      throw new DiskBalancerException("Invalid plan.",
          DiskBalancerException.Result.INVALID_PLAN);
    }

    if ((planID == null) ||
        (planID.length() != sha512Length) ||
        !DigestUtils.sha512Hex(plan.getBytes(Charset.forName("UTF-8")))
            .equalsIgnoreCase(planID)) {
      LOG.error("Disk Balancer - Invalid plan hash.");
      throw new DiskBalancerException("Invalid or mis-matched hash.",
          DiskBalancerException.Result.INVALID_PLAN_HASH);
    }

    try {
      return NodePlan.parseJson(plan);
    } catch (IOException ex) {
      throw new DiskBalancerException("Parsing plan failed.", ex,
          DiskBalancerException.Result.MALFORMED_PLAN);
    }
  }

  /**
   * Verifies that this plan is not older than 24 hours.
   *
   * @param plan - Node Plan
   */
  private void verifyTimeStamp(NodePlan plan) throws DiskBalancerException {
    long now = Time.now();
    long planTime = plan.getTimeStamp();

    // TODO : Support Valid Plan hours as a user configurable option.
    if ((planTime +
        (TimeUnit.HOURS.toMillis(
            DiskBalancerConstants.DISKBALANCER_VALID_PLAN_HOURS))) < now) {
      String hourString = "Plan was generated more than " +
          Integer.toString(DiskBalancerConstants.DISKBALANCER_VALID_PLAN_HOURS)
          + " hours ago.";
      LOG.error("Disk Balancer - " + hourString);
      throw new DiskBalancerException(hourString,
          DiskBalancerException.Result.OLD_PLAN_SUBMITTED);
    }
  }

  /**
   * Verify Node UUID.
   *
   * @param plan - Node Plan
   */
  private void verifyNodeUUID(NodePlan plan) throws DiskBalancerException {
    if ((plan.getNodeUUID() == null) ||
        !plan.getNodeUUID().equals(this.dataNodeUUID)) {
      LOG.error("Disk Balancer - Plan was generated for another node.");
      throw new DiskBalancerException(
          "Plan was generated for another node.",
          DiskBalancerException.Result.DATANODE_ID_MISMATCH);
    }
  }

  /**
   * Convert a node plan to DiskBalancerWorkItem that Datanode can execute.
   *
   * @param plan - Node Plan
   */
  private void createWorkPlan(NodePlan plan) throws DiskBalancerException {
    Preconditions.checkState(lock.isHeldByCurrentThread());

    // Cleanup any residual work in the map.
    workMap.clear();
    Map<String, FsVolumeSpi> pathMap = getStorageIDToVolumeMap();

    for (Step step : plan.getVolumeSetPlans()) {
      String sourceuuid = step.getSourceVolume().getUuid();
      String destinationuuid = step.getDestinationVolume().getUuid();

      FsVolumeSpi sourceVol = pathMap.get(sourceuuid);
      if (sourceVol == null) {
        LOG.error("Disk Balancer - Unable to find source volume. submitPlan " +
            "failed.");
        throw new DiskBalancerException("Unable to find source volume.",
            DiskBalancerException.Result.INVALID_VOLUME);
      }

      FsVolumeSpi destVol = pathMap.get(destinationuuid);
      if (destVol == null) {
        LOG.error("Disk Balancer - Unable to find destination volume. " +
            "submitPlan failed.");
        throw new DiskBalancerException("Unable to find destination volume.",
            DiskBalancerException.Result.INVALID_VOLUME);
      }
      createWorkPlan(sourceVol, destVol, step);
    }
  }

  /**
   * Returns a path to Volume Map.
   *
   * @return Map
   * @throws DiskBalancerException
   */
  private Map<String, FsVolumeSpi> getStorageIDToVolumeMap()
      throws DiskBalancerException {
    Map<String, FsVolumeSpi> pathMap = new HashMap<>();
    FsDatasetSpi.FsVolumeReferences references;
    try {
      synchronized (this.dataset) {
        references = this.dataset.getFsVolumeReferences();
        for (int ndx = 0; ndx < references.size(); ndx++) {
          FsVolumeSpi vol = references.get(ndx);
          pathMap.put(vol.getStorageID(), vol);
        }
        references.close();
      }
    } catch (IOException ex) {
      LOG.error("Disk Balancer - Internal Error.", ex);
      throw new DiskBalancerException("Internal error", ex,
          DiskBalancerException.Result.INTERNAL_ERROR);
    }
    return pathMap;
  }

  /**
   * Starts Executing the plan, exits when the plan is done executing.
   */
  private void executePlan() {
    Preconditions.checkState(lock.isHeldByCurrentThread());
    this.blockMover.setRunnable();
    if (this.scheduler.isShutdown()) {
      this.scheduler = Executors.newSingleThreadExecutor();
    }

    this.future = scheduler.submit(new Runnable() {
      @Override
      public void run() {
        Thread.currentThread().setName("DiskBalancerThread");
        LOG.info("Executing Disk balancer plan. Plan ID -  " + planID);
        try {
          for (Map.Entry<VolumePair, DiskBalancerWorkItem> entry :
              workMap.entrySet()) {
            blockMover.copyBlocks(entry.getKey(), entry.getValue());
          }
        } finally {
          blockMover.setExitFlag();
        }
      }
    });
  }

  /**
   * Insert work items to work map.
   *
   * @param source - Source vol
   * @param dest   - destination volume
   * @param step   - Move Step
   */
  private void createWorkPlan(FsVolumeSpi source, FsVolumeSpi dest,
                              Step step) throws DiskBalancerException {

    if (source.getStorageID().equals(dest.getStorageID())) {
      LOG.info("Disk Balancer - source & destination volumes are same.");
      throw new DiskBalancerException("source and destination volumes are " +
          "same.", DiskBalancerException.Result.INVALID_MOVE);
    }
    VolumePair pair = new VolumePair(source, dest);
    long bytesToMove = step.getBytesToMove();
    // In case we have a plan with more than
    // one line of same <source, dest>
    // we compress that into one work order.
    if (workMap.containsKey(pair)) {
      bytesToMove += workMap.get(pair).getBytesToCopy();
    }

    DiskBalancerWorkItem work = new DiskBalancerWorkItem(bytesToMove, 0);

    // all these values can be zero, if so we will use
    // values from configuration.
    work.setBandwidth(step.getBandwidth());
    work.setTolerancePercent(step.getTolerancePercent());
    work.setMaxDiskErrors(step.getMaxDiskErrors());
    workMap.put(pair, work);
  }

  /**
   * BlockMover supports moving blocks across Volumes.
   */
  public interface BlockMover {
    /**
     * Copies blocks from a set of volumes.
     *
     * @param pair - Source and Destination Volumes.
     * @param item - Number of bytes to move from volumes.
     */
    void copyBlocks(VolumePair pair, DiskBalancerWorkItem item);

    /**
     * Begin the actual copy operations. This is useful in testing.
     */
    void setRunnable();

    /**
     * Tells copyBlocks to exit from the copy routine.
     */
    void setExitFlag();

    /**
     * Returns a pointer to the current dataset we are operating against.
     *
     * @return FsDatasetSpi
     */
    FsDatasetSpi getDataset();

    /**
     * Returns time when this plan started executing.
     *
     * @return Start time in milliseconds.
     */
    long getStartTime();

    /**
     * Number of seconds elapsed.
     *
     * @return time in seconds
     */
    long getElapsedSeconds();

  }

  /**
   * Holds references to actual volumes that we will be operating against.
   */
  public static class VolumePair {
    private final FsVolumeSpi source;
    private final FsVolumeSpi dest;

    /**
     * Constructs a volume pair.
     *
     * @param source - Source Volume
     * @param dest   - Destination Volume
     */
    public VolumePair(FsVolumeSpi source, FsVolumeSpi dest) {
      this.source = source;
      this.dest = dest;
    }

    /**
     * gets source volume.
     *
     * @return volume
     */
    public FsVolumeSpi getSource() {
      return source;
    }

    /**
     * Gets Destination volume.
     *
     * @return volume.
     */
    public FsVolumeSpi getDest() {
      return dest;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }

      VolumePair that = (VolumePair) o;
      return source.equals(that.source) && dest.equals(that.dest);
    }

    @Override
    public int hashCode() {
      int result = source.getBasePath().hashCode();
      result = 31 * result + dest.getBasePath().hashCode();
      return result;
    }
  }

  /**
   * Actual DataMover class for DiskBalancer.
   * <p>
   */
  public static class DiskBalancerMover implements BlockMover {
    private final FsDatasetSpi dataset;
    private long diskBandwidth;
    private long blockTolerance;
    private long maxDiskErrors;
    private int poolIndex;
    private AtomicBoolean shouldRun;
    private long startTime;
    private long secondsElapsed;

    /**
     * Constructs diskBalancerMover.
     *
     * @param dataset Dataset
     * @param conf    Configuration
     */
    public DiskBalancerMover(FsDatasetSpi dataset, Configuration conf) {
      this.dataset = dataset;
      shouldRun = new AtomicBoolean(false);

      this.diskBandwidth = conf.getLong(
          DFSConfigKeys.DFS_DISK_BALANCER_MAX_DISK_THROUGHPUT,
          DFSConfigKeys.DFS_DISK_BALANCER_MAX_DISK_THROUGHPUT_DEFAULT);

      this.blockTolerance = conf.getLong(
          DFSConfigKeys.DFS_DISK_BALANCER_BLOCK_TOLERANCE,
          DFSConfigKeys.DFS_DISK_BALANCER_BLOCK_TOLERANCE_DEFAULT);

      this.maxDiskErrors = conf.getLong(
          DFSConfigKeys.DFS_DISK_BALANCER_MAX_DISK_ERRORS,
          DFSConfigKeys.DFS_DISK_BALANCER_MAX_DISK_ERRORS_DEFAULT);

      // Since these are user provided values make sure it is sane
      // or ignore faulty values.
      if (this.diskBandwidth <= 0) {
        LOG.debug("Found 0 or less as max disk throughput, ignoring config " +
            "value. value : " + diskBandwidth);
        diskBandwidth =
            DFSConfigKeys.DFS_DISK_BALANCER_MAX_DISK_THROUGHPUT_DEFAULT;
      }

      if (this.blockTolerance <= 0) {
        LOG.debug("Found 0 or less for block tolerance value, ignoring config" +
            "value. value : " + blockTolerance);
        blockTolerance =
            DFSConfigKeys.DFS_DISK_BALANCER_BLOCK_TOLERANCE_DEFAULT;

      }

      if (this.maxDiskErrors < 0) {
        LOG.debug("Found  less than 0 for maxDiskErrors value, ignoring " +
            "config value. value : " + maxDiskErrors);
        maxDiskErrors =
            DFSConfigKeys.DFS_DISK_BALANCER_MAX_DISK_ERRORS_DEFAULT;
      }
    }

    /**
     * Sets Diskmover copyblocks into runnable state.
     */
    @Override
    public void setRunnable() {
      this.shouldRun.set(true);
    }

    /**
     * Signals copy block to exit.
     */
    @Override
    public void setExitFlag() {
      this.shouldRun.set(false);
    }

    /**
     * Returns the shouldRun boolean flag.
     */
    public boolean shouldRun() {
      return this.shouldRun.get();
    }

    /**
     * Checks if a given block is less than needed size to meet our goal.
     *
     * @param blockSize - block len
     * @param item      - Work item
     * @return true if this block meets our criteria, false otherwise.
     */
    private boolean isLessThanNeeded(long blockSize,
                                     DiskBalancerWorkItem item) {
      long bytesToCopy = item.getBytesToCopy() - item.getBytesCopied();
      bytesToCopy = bytesToCopy +
          ((bytesToCopy * getBlockTolerancePercentage(item)) / 100);
      return (blockSize <= bytesToCopy) ? true : false;
    }

    /**
     * Returns the default block tolerance if the plan does not have value of
     * tolerance specified.
     *
     * @param item - DiskBalancerWorkItem
     * @return long
     */
    private long getBlockTolerancePercentage(DiskBalancerWorkItem item) {
      return item.getTolerancePercent() <= 0 ? this.blockTolerance :
          item.getTolerancePercent();
    }

    /**
     * Inflates bytesCopied and returns true or false. This allows us to stop
     * copying if we have reached close enough.
     *
     * @param item DiskBalancerWorkItem
     * @return -- false if we need to copy more, true if we are done
     */
    private boolean isCloseEnough(DiskBalancerWorkItem item) {
      long temp = item.getBytesCopied() +
          ((item.getBytesCopied() * getBlockTolerancePercentage(item)) / 100);
      return (item.getBytesToCopy() >= temp) ? false : true;
    }

    /**
     * Returns disk bandwidth associated with this plan, if none is specified
     * returns the global default.
     *
     * @param item DiskBalancerWorkItem.
     * @return MB/s - long
     */
    private long getDiskBandwidth(DiskBalancerWorkItem item) {
      return item.getBandwidth() <= 0 ? this.diskBandwidth : item
          .getBandwidth();
    }

    /**
     * Computes sleep delay needed based on the block that just got copied. we
     * copy using a burst mode, that is we let the copy proceed in full
     * throttle. Once a copy is done, we compute how many bytes have been
     * transferred and try to average it over the user specified bandwidth. In
     * other words, This code implements a poor man's token bucket algorithm for
     * traffic shaping.
     *
     * @param bytesCopied - byteCopied.
     * @param timeUsed    in milliseconds
     * @param item        DiskBalancerWorkItem
     * @return sleep delay in Milliseconds.
     */
    private long computeDelay(long bytesCopied, long timeUsed,
                              DiskBalancerWorkItem item) {

      // we had an overflow, ignore this reading and continue.
      if (timeUsed == 0) {
        return 0;
      }
      final int megaByte = 1024 * 1024;
      long bytesInMB = bytesCopied / megaByte;
      long lastThroughput = bytesInMB / SECONDS.convert(timeUsed,
          TimeUnit.MILLISECONDS);
      long delay = (bytesInMB / getDiskBandwidth(item)) - lastThroughput;
      return (delay <= 0) ? 0 : MILLISECONDS.convert(delay, TimeUnit.SECONDS);
    }

    /**
     * Returns maximum errors to tolerate for the specific plan or the default.
     *
     * @param item - DiskBalancerWorkItem
     * @return maximum error counts to tolerate.
     */
    private long getMaxError(DiskBalancerWorkItem item) {
      return item.getMaxDiskErrors() <= 0 ? this.maxDiskErrors :
          item.getMaxDiskErrors();
    }

    /**
     * Gets the next block that we can copy, returns null if we cannot find a
     * block that fits our parameters or if have run out of blocks.
     *
     * @param iter Block Iter
     * @param item - Work item
     * @return Extended block or null if no copyable block is found.
     */
    private ExtendedBlock getBlockToCopy(FsVolumeSpi.BlockIterator iter,
                                         DiskBalancerWorkItem item) {
      while (!iter.atEnd() && item.getErrorCount() < getMaxError(item)) {
        try {
          ExtendedBlock block = iter.nextBlock();

          // A valid block is a finalized block, we iterate until we get
          // finalized blocks
          if (!this.dataset.isValidBlock(block)) {
            continue;
          }

          // We don't look for the best, we just do first fit
          if (isLessThanNeeded(block.getNumBytes(), item)) {
            return block;
          }

        } catch (IOException e) {
          item.incErrorCount();
        }
      }

      if (item.getErrorCount() >= getMaxError(item)) {
        item.setErrMsg("Error count exceeded.");
        LOG.info("Maximum error count exceeded. Error count: {} Max error:{} "
            , item.getErrorCount(), item.getMaxDiskErrors());
      }

      return null;
    }

    /**
     * Opens all Block pools on a given volume.
     *
     * @param source    Source
     * @param poolIters List of PoolIters to maintain.
     */
    private void openPoolIters(FsVolumeSpi source, List<FsVolumeSpi
        .BlockIterator> poolIters) {
      Preconditions.checkNotNull(source);
      Preconditions.checkNotNull(poolIters);

      for (String blockPoolID : source.getBlockPoolList()) {
        poolIters.add(source.newBlockIterator(blockPoolID,
            "DiskBalancerSource"));
      }
    }

    /**
     * Returns the next block that we copy from all the block pools. This
     * function looks across all block pools to find the next block to copy.
     *
     * @param poolIters - List of BlockIterators
     * @return ExtendedBlock.
     */
    ExtendedBlock getNextBlock(List<FsVolumeSpi.BlockIterator> poolIters,
                               DiskBalancerWorkItem item) {
      Preconditions.checkNotNull(poolIters);
      int currentCount = 0;
      ExtendedBlock block = null;
      while (block == null && currentCount < poolIters.size()) {
        currentCount++;
        poolIndex = poolIndex++ % poolIters.size();
        FsVolumeSpi.BlockIterator currentPoolIter = poolIters.get(poolIndex);
        block = getBlockToCopy(currentPoolIter, item);
      }

      if (block == null) {
        try {
          item.setErrMsg("No source blocks found to move.");
          LOG.error("No movable source blocks found. {}", item.toJson());
        } catch (IOException e) {
          LOG.error("Unable to get json from Item.");
        }
      }
      return block;
    }

    /**
     * Close all Pool Iters.
     *
     * @param poolIters List of BlockIters
     */
    private void closePoolIters(List<FsVolumeSpi.BlockIterator> poolIters) {
      Preconditions.checkNotNull(poolIters);
      for (FsVolumeSpi.BlockIterator iter : poolIters) {
        try {
          iter.close();
        } catch (IOException ex) {
          LOG.error("Error closing a block pool iter. ex: {}", ex);
        }
      }
    }

    /**
     * Copies blocks from a set of volumes.
     *
     * @param pair - Source and Destination Volumes.
     * @param item - Number of bytes to move from volumes.
     */
    @Override
    public void copyBlocks(VolumePair pair, DiskBalancerWorkItem item) {
      FsVolumeSpi source = pair.getSource();
      FsVolumeSpi dest = pair.getDest();
      List<FsVolumeSpi.BlockIterator> poolIters = new LinkedList<>();
      startTime = Time.now();
      item.setStartTime(startTime);
      secondsElapsed = 0;

      if (source.isTransientStorage() || dest.isTransientStorage()) {
        return;
      }

      try {
        openPoolIters(source, poolIters);
        if (poolIters.size() == 0) {
          LOG.error("No block pools found on volume. volume : {}. Exiting.",
              source.getBasePath());
          return;
        }

        while (shouldRun()) {
          try {

            // Check for the max error count constraint.
            if (item.getErrorCount() > getMaxError(item)) {
              LOG.error("Exceeded the max error count. source {}, dest: {} " +
                      "error count: {}", source.getBasePath(),
                  dest.getBasePath(), item.getErrorCount());
              break;
            }

            // Check for the block tolerance constraint.
            if (isCloseEnough(item)) {
              LOG.info("Copy from {} to {} done. copied {} bytes and {} " +
                      "blocks.",
                  source.getBasePath(), dest.getBasePath(),
                  item.getBytesCopied(), item.getBlocksCopied());
              break;
            }

            ExtendedBlock block = getNextBlock(poolIters, item);
            // we are not able to find any blocks to copy.
            if (block == null) {
              LOG.error("No source blocks, exiting the copy. Source: {}, " +
                  "dest:{}", source.getBasePath(), dest.getBasePath());
              break;
            }

            // check if someone told us exit, treat this as an interruption
            // point
            // for the thread, since both getNextBlock and moveBlocAcrossVolume
            // can take some time.
            if (!shouldRun()) {
              break;
            }

            long timeUsed;
            // There is a race condition here, but we will get an IOException
            // if dest has no space, which we handle anyway.
            if (dest.getAvailable() > item.getBytesToCopy()) {
              long begin = System.nanoTime();
              this.dataset.moveBlockAcrossVolumes(block, dest);
              long now = System.nanoTime();
              timeUsed = (now - begin) > 0 ? now - begin : 0;
            } else {

              // Technically it is possible for us to find a smaller block and
              // make another copy, but opting for the safer choice of just
              // exiting here.
              LOG.error("Destination volume: {} does not have enough space to" +
                  " accommodate a block. Block Size: {} Exiting from" +
                  " copyBlocks.", dest.getBasePath(), block.getNumBytes());
              break;
            }

            LOG.debug("Moved block with size {} from  {} to {}",
                block.getNumBytes(), source.getBasePath(),
                dest.getBasePath());

            // Check for the max throughput constraint.
            // We sleep here to keep the promise that we will not
            // copy more than Max MB/sec. we sleep enough time
            // to make sure that our promise is good on average.
            // Because we sleep, if a shutdown or cancel call comes in
            // we exit via Thread Interrupted exception.
            Thread.sleep(computeDelay(block.getNumBytes(), timeUsed, item));

            // We delay updating the info to avoid confusing the user.
            // This way we report the copy only if it is under the
            // throughput threshold.
            item.incCopiedSoFar(block.getNumBytes());
            item.incBlocksCopied();
            secondsElapsed = TimeUnit.MILLISECONDS.toSeconds(Time.now() -
                startTime);
            item.setSecondsElapsed(secondsElapsed);
          } catch (IOException ex) {
            LOG.error("Exception while trying to copy blocks. error: {}", ex);
            item.incErrorCount();
          } catch (InterruptedException e) {
            LOG.error("Copy Block Thread interrupted, exiting the copy.");
            Thread.currentThread().interrupt();
            item.incErrorCount();
            this.setExitFlag();
          }
        }
      } finally {
        // Close all Iters.
        closePoolIters(poolIters);
      }
    }

    /**
     * Returns a pointer to the current dataset we are operating against.
     *
     * @return FsDatasetSpi
     */
    @Override
    public FsDatasetSpi getDataset() {
      return dataset;
    }

    /**
     * Returns time when this plan started executing.
     *
     * @return Start time in milliseconds.
     */
    @Override
    public long getStartTime() {
      return startTime;
    }

    /**
     * Number of seconds elapsed.
     *
     * @return time in seconds
     */
    @Override
    public long getElapsedSeconds() {
      return secondsElapsed;
    }
  }
}
