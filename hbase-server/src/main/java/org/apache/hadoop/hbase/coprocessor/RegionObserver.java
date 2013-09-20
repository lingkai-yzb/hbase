/*
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hbase.coprocessor;

import java.io.IOException;
import java.util.List;
import java.util.NavigableSet;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.Coprocessor;
import org.apache.hadoop.hbase.HBaseInterfaceAudience;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.client.Append;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Increment;
import org.apache.hadoop.hbase.client.Mutation;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.Durability;
import org.apache.hadoop.hbase.filter.CompareFilter.CompareOp;
import org.apache.hadoop.hbase.filter.ByteArrayComparable;
import org.apache.hadoop.hbase.regionserver.HRegion;
import org.apache.hadoop.hbase.regionserver.InternalScanner;
import org.apache.hadoop.hbase.regionserver.KeyValueScanner;
import org.apache.hadoop.hbase.regionserver.MiniBatchOperationInProgress;
import org.apache.hadoop.hbase.regionserver.RegionScanner;
import org.apache.hadoop.hbase.regionserver.ScanType;
import org.apache.hadoop.hbase.regionserver.Store;
import org.apache.hadoop.hbase.regionserver.StoreFile;
import org.apache.hadoop.hbase.regionserver.StoreFileScanner;
import org.apache.hadoop.hbase.regionserver.compactions.CompactionRequest;
import org.apache.hadoop.hbase.regionserver.wal.HLogKey;
import org.apache.hadoop.hbase.regionserver.wal.WALEdit;

import com.google.common.collect.ImmutableList;
import org.apache.hadoop.hbase.util.Pair;

/**
 * Coprocessors implement this interface to observe and mediate client actions
 * on the region.
 */
@InterfaceAudience.LimitedPrivate(HBaseInterfaceAudience.COPROC)
@InterfaceStability.Evolving
public interface RegionObserver extends Coprocessor {

  /**
   * Called before the region is reported as open to the master.
   * @param c the environment provided by the region server
   * @throws IOException if an error occurred on the coprocessor
   */
  void preOpen(final ObserverContext<RegionCoprocessorEnvironment> c) throws IOException;

  /**
   * Called after the region is reported as open to the master.
   * @param c the environment provided by the region server
   */
  void postOpen(final ObserverContext<RegionCoprocessorEnvironment> c);

  /**
   * Called before a memstore is flushed to disk and prior to creating the scanner to read from
   * the memstore.  To override or modify how a memstore is flushed,
   * implementing classes can return a new scanner to provide the KeyValues to be
   * stored into the new {@code StoreFile} or null to perform the default processing.
   * Calling {@link org.apache.hadoop.hbase.coprocessor.ObserverContext#bypass()} has no
   * effect in this hook.
   * @param c the environment provided by the region server
   * @param store the store being flushed
   * @param memstoreScanner the scanner for the memstore that is flushed
   * @param s the base scanner, if not {@code null}, from previous RegionObserver in the chain
   * @return the scanner to use during the flush.  {@code null} if the default implementation
   * is to be used.
   * @throws IOException if an error occurred on the coprocessor
   */
  InternalScanner preFlushScannerOpen(final ObserverContext<RegionCoprocessorEnvironment> c,
      final Store store, final KeyValueScanner memstoreScanner, final InternalScanner s)
      throws IOException;

  /**
   * Called before the memstore is flushed to disk.
   * @param c the environment provided by the region server
   * @throws IOException if an error occurred on the coprocessor
   * @deprecated use {@link #preFlush(ObserverContext, Store, InternalScanner)} instead
   */
  void preFlush(final ObserverContext<RegionCoprocessorEnvironment> c) throws IOException;

  /**
   * Called before a Store's memstore is flushed to disk.
   * @param c the environment provided by the region server
   * @param store the store where compaction is being requested
   * @param scanner the scanner over existing data used in the store file
   * @return the scanner to use during compaction.  Should not be {@code null}
   * unless the implementation is writing new store files on its own.
   * @throws IOException if an error occurred on the coprocessor
   */
  InternalScanner preFlush(final ObserverContext<RegionCoprocessorEnvironment> c, final Store store,
      final InternalScanner scanner) throws IOException;

  /**
   * Called after the memstore is flushed to disk.
   * @param c the environment provided by the region server
   * @throws IOException if an error occurred on the coprocessor
   * @deprecated use {@link #preFlush(ObserverContext, Store, InternalScanner)} instead.
   */
  void postFlush(final ObserverContext<RegionCoprocessorEnvironment> c) throws IOException;

  /**
   * Called after a Store's memstore is flushed to disk.
   * @param c the environment provided by the region server
   * @param store the store being flushed
   * @param resultFile the new store file written out during compaction
   * @throws IOException if an error occurred on the coprocessor
   */
  void postFlush(final ObserverContext<RegionCoprocessorEnvironment> c, final Store store,
      final StoreFile resultFile) throws IOException;

  /**
   * Called prior to selecting the {@link StoreFile StoreFiles} to compact from the list of
   * available candidates. To alter the files used for compaction, you may mutate the passed in list
   * of candidates.
   * @param c the environment provided by the region server
   * @param store the store where compaction is being requested
   * @param candidates the store files currently available for compaction
   * @param request custom compaction request
   * @throws IOException if an error occurred on the coprocessor
   */
  void preCompactSelection(final ObserverContext<RegionCoprocessorEnvironment> c,
      final Store store, final List<StoreFile> candidates, final CompactionRequest request)
      throws IOException;

  /**
   * Called prior to selecting the {@link StoreFile}s to compact from the list of available
   * candidates. To alter the files used for compaction, you may mutate the passed in list of
   * candidates.
   * @param c the environment provided by the region server
   * @param store the store where compaction is being requested
   * @param candidates the store files currently available for compaction
   * @throws IOException if an error occurred on the coprocessor
   * @deprecated Use {@link #preCompactSelection(ObserverContext, Store, List, CompactionRequest)}
   *             instead
   */
  @Deprecated
  void preCompactSelection(final ObserverContext<RegionCoprocessorEnvironment> c,
      final Store store, final List<StoreFile> candidates) throws IOException;

  /**
   * Called after the {@link StoreFile}s to compact have been selected from the available
   * candidates.
   * @param c the environment provided by the region server
   * @param store the store being compacted
   * @param selected the store files selected to compact
   * @param request custom compaction request
   */
  void postCompactSelection(final ObserverContext<RegionCoprocessorEnvironment> c,
      final Store store, final ImmutableList<StoreFile> selected, CompactionRequest request);

  /**
   * Called after the {@link StoreFile}s to compact have been selected from the available
   * candidates.
   * @param c the environment provided by the region server
   * @param store the store being compacted
   * @param selected the store files selected to compact
   * @deprecated use {@link #postCompactSelection(ObserverContext, Store, ImmutableList,
   *             CompactionRequest)} instead.
   */
  @Deprecated
  void postCompactSelection(final ObserverContext<RegionCoprocessorEnvironment> c,
      final Store store, final ImmutableList<StoreFile> selected);

  /**
   * Called prior to writing the {@link StoreFile}s selected for compaction into a new
   * {@code StoreFile}. To override or modify the compaction process, implementing classes have two
   * options:
   * <ul>
   * <li>Wrap the provided {@link InternalScanner} with a custom implementation that is returned
   * from this method. The custom scanner can then inspect {@link KeyValue}s from the wrapped
   * scanner, applying its own policy to what gets written.</li>
   * <li>Call {@link org.apache.hadoop.hbase.coprocessor.ObserverContext#bypass()} and provide a
   * custom implementation for writing of new {@link StoreFile}s. <strong>Note: any implementations
   * bypassing core compaction using this approach must write out new store files themselves or the
   * existing data will no longer be available after compaction.</strong></li>
   * </ul>
   * @param c the environment provided by the region server
   * @param store the store being compacted
   * @param scanner the scanner over existing data used in the store file rewriting
   * @param scanType type of Scan
   * @param request the requested compaction
   * @return the scanner to use during compaction. Should not be {@code null} unless the
   *         implementation is writing new store files on its own.
   * @throws IOException if an error occurred on the coprocessor
   */
  InternalScanner preCompact(final ObserverContext<RegionCoprocessorEnvironment> c,
      final Store store, final InternalScanner scanner, final ScanType scanType,
      CompactionRequest request) throws IOException;

  /**
   * Called prior to writing the {@link StoreFile}s selected for compaction into a new
   * {@code StoreFile}. To override or modify the compaction process, implementing classes have two
   * options:
   * <ul>
   * <li>Wrap the provided {@link InternalScanner} with a custom implementation that is returned
   * from this method. The custom scanner can then inspect {@link KeyValue}s from the wrapped
   * scanner, applying its own policy to what gets written.</li>
   * <li>Call {@link org.apache.hadoop.hbase.coprocessor.ObserverContext#bypass()} and provide a
   * custom implementation for writing of new {@link StoreFile}s. <strong>Note: any implementations
   * bypassing core compaction using this approach must write out new store files themselves or the
   * existing data will no longer be available after compaction.</strong></li>
   * </ul>
   * @param c the environment provided by the region server
   * @param store the store being compacted
   * @param scanner the scanner over existing data used in the store file rewriting
   * @param scanType type of Scan
   * @return the scanner to use during compaction. Should not be {@code null} unless the
   *         implementation is writing new store files on its own.
   * @throws IOException if an error occurred on the coprocessor
   * @deprecated use
   *             {@link #preCompact(ObserverContext, Store, InternalScanner,
   *             ScanType, CompactionRequest)} instead
   */
  @Deprecated
  InternalScanner preCompact(final ObserverContext<RegionCoprocessorEnvironment> c,
      final Store store, final InternalScanner scanner, final ScanType scanType) throws IOException;

  /**
   * Called prior to writing the {@link StoreFile}s selected for compaction into a new
   * {@code StoreFile} and prior to creating the scanner used to read the input files. To override
   * or modify the compaction process, implementing classes can return a new scanner to provide the
   * KeyValues to be stored into the new {@code StoreFile} or null to perform the default
   * processing. Calling {@link org.apache.hadoop.hbase.coprocessor.ObserverContext#bypass()} has no
   * effect in this hook.
   * @param c the environment provided by the region server
   * @param store the store being compacted
   * @param scanners the list {@link StoreFileScanner}s to be read from
   * @param scanType the {@link ScanType} indicating whether this is a major or minor compaction
   * @param earliestPutTs timestamp of the earliest put that was found in any of the involved store
   *          files
   * @param s the base scanner, if not {@code null}, from previous RegionObserver in the chain
   * @param request the requested compaction
   * @return the scanner to use during compaction. {@code null} if the default implementation is to
   *         be used.
   * @throws IOException if an error occurred on the coprocessor
   */
  InternalScanner preCompactScannerOpen(final ObserverContext<RegionCoprocessorEnvironment> c,
      final Store store, List<? extends KeyValueScanner> scanners, final ScanType scanType,
      final long earliestPutTs, final InternalScanner s, CompactionRequest request)
      throws IOException;

  /**
   * Called prior to writing the {@link StoreFile}s selected for compaction into a new
   * {@code StoreFile} and prior to creating the scanner used to read the input files. To override
   * or modify the compaction process, implementing classes can return a new scanner to provide the
   * KeyValues to be stored into the new {@code StoreFile} or null to perform the default
   * processing. Calling {@link org.apache.hadoop.hbase.coprocessor.ObserverContext#bypass()} has no
   * effect in this hook.
   * @param c the environment provided by the region server
   * @param store the store being compacted
   * @param scanners the list {@link StoreFileScanner}s to be read from
   * @param scanType the {@link ScanType} indicating whether this is a major or minor compaction
   * @param earliestPutTs timestamp of the earliest put that was found in any of the involved store
   *          files
   * @param s the base scanner, if not {@code null}, from previous RegionObserver in the chain
   * @return the scanner to use during compaction. {@code null} if the default implementation is to
   *         be used.
   * @throws IOException if an error occurred on the coprocessor
   * @deprecated Use
   *             {@link #preCompactScannerOpen(ObserverContext, Store, List, ScanType, long,
   *             InternalScanner, CompactionRequest)} instead.
   */
  @Deprecated
  InternalScanner preCompactScannerOpen(final ObserverContext<RegionCoprocessorEnvironment> c,
      final Store store, List<? extends KeyValueScanner> scanners, final ScanType scanType,
      final long earliestPutTs, final InternalScanner s) throws IOException;

  /**
   * Called after compaction has completed and the new store file has been moved in to place.
   * @param c the environment provided by the region server
   * @param store the store being compacted
   * @param resultFile the new store file written out during compaction
   * @param request the requested compaction
   * @throws IOException if an error occurred on the coprocessor
   */
  void postCompact(final ObserverContext<RegionCoprocessorEnvironment> c, final Store store,
      StoreFile resultFile, CompactionRequest request) throws IOException;

  /**
   * Called after compaction has completed and the new store file has been moved in to place.
   * @param c the environment provided by the region server
   * @param store the store being compacted
   * @param resultFile the new store file written out during compaction
   * @throws IOException if an error occurred on the coprocessor
   * @deprecated Use {@link #postCompact(ObserverContext, Store, StoreFile, CompactionRequest)}
   *             instead
   */
  @Deprecated
  void postCompact(final ObserverContext<RegionCoprocessorEnvironment> c, final Store store,
      StoreFile resultFile) throws IOException;

  /**
   * Called before the region is split.
   * @param c the environment provided by the region server
   * (e.getRegion() returns the parent region)
   * @throws IOException if an error occurred on the coprocessor
   * @deprecated Use preSplit(
   *    final ObserverContext<RegionCoprocessorEnvironment> c, byte[] splitRow)
   */
  void preSplit(final ObserverContext<RegionCoprocessorEnvironment> c) throws IOException;

  /**
   * Called before the region is split.
   * @param c the environment provided by the region server
   * (e.getRegion() returns the parent region)
   * @throws IOException if an error occurred on the coprocessor
   */
  void preSplit(final ObserverContext<RegionCoprocessorEnvironment> c, byte[] splitRow)
      throws IOException;

  /**
   * Called after the region is split.
   * @param c the environment provided by the region server
   * (e.getRegion() returns the parent region)
   * @param l the left daughter region
   * @param r the right daughter region
   * @throws IOException if an error occurred on the coprocessor
   * @deprecated Use postCompleteSplit() instead
   */
  void postSplit(final ObserverContext<RegionCoprocessorEnvironment> c, final HRegion l,
      final HRegion r) throws IOException;

  /**
   * This will be called before the roll back of the split region is completed 
   * @param ctx
   * @throws IOException
   */
  void preRollBackSplit(final ObserverContext<RegionCoprocessorEnvironment> ctx) throws IOException;

  /**
   * This will be called after the roll back of the split region is completed
   * @param ctx
   * @throws IOException
   */
  void postRollBackSplit(final ObserverContext<RegionCoprocessorEnvironment> ctx)
    throws IOException;

  /**
   * Called after any split request is processed.  This will be called irrespective of success or
   * failure of the split.
   * @param ctx
   * @throws IOException
   */
  void postCompleteSplit(final ObserverContext<RegionCoprocessorEnvironment> ctx)
    throws IOException;
  /**
   * Called before the region is reported as closed to the master.
   * @param c the environment provided by the region server
   * @param abortRequested true if the region server is aborting
   * @throws IOException 
   */
  void preClose(final ObserverContext<RegionCoprocessorEnvironment> c,
      boolean abortRequested) throws IOException;

  /**
   * Called after the region is reported as closed to the master.
   * @param c the environment provided by the region server
   * @param abortRequested true if the region server is aborting
   */
  void postClose(final ObserverContext<RegionCoprocessorEnvironment> c,
      boolean abortRequested);

  /**
   * Called before a client makes a GetClosestRowBefore request.
   * <p>
   * Call CoprocessorEnvironment#bypass to skip default actions
   * <p>
   * Call CoprocessorEnvironment#complete to skip any subsequent chained
   * coprocessors
   * @param c the environment provided by the region server
   * @param row the row
   * @param family the family
   * @param result The result to return to the client if default processing
   * is bypassed. Can be modified. Will not be used if default processing
   * is not bypassed.
   * @throws IOException if an error occurred on the coprocessor
   */
  void preGetClosestRowBefore(final ObserverContext<RegionCoprocessorEnvironment> c,
      final byte [] row, final byte [] family, final Result result)
    throws IOException;

  /**
   * Called after a client makes a GetClosestRowBefore request.
   * <p>
   * Call CoprocessorEnvironment#complete to skip any subsequent chained
   * coprocessors
   * @param c the environment provided by the region server
   * @param row the row
   * @param family the desired family
   * @param result the result to return to the client, modify as necessary
   * @throws IOException if an error occurred on the coprocessor
   */
  void postGetClosestRowBefore(final ObserverContext<RegionCoprocessorEnvironment> c,
      final byte [] row, final byte [] family, final Result result)
    throws IOException;

  /**
   * Called before the client performs a Get
   * <p>
   * Call CoprocessorEnvironment#bypass to skip default actions
   * <p>
   * Call CoprocessorEnvironment#complete to skip any subsequent chained
   * coprocessors
   * @param c the environment provided by the region server
   * @param get the Get request
   * @param result The result to return to the client if default processing
   * is bypassed. Can be modified. Will not be used if default processing
   * is not bypassed.
   * @throws IOException if an error occurred on the coprocessor
   */
  void preGetOp(final ObserverContext<RegionCoprocessorEnvironment> c, final Get get,
      final List<Cell> result)
    throws IOException;

  /**
   * WARNING: please override preGetOp instead of this method.  This is to maintain some
   * compatibility and to ease the transition from 0.94 -> 0.96.
   */
  @Deprecated
  void preGet(final ObserverContext<RegionCoprocessorEnvironment> c, final Get get,
      final List<KeyValue> result)
    throws IOException;

  /**
   * Called after the client performs a Get
   * <p>
   * Call CoprocessorEnvironment#complete to skip any subsequent chained
   * coprocessors
   * @param c the environment provided by the region server
   * @param get the Get request
   * @param result the result to return to the client, modify as necessary
   * @throws IOException if an error occurred on the coprocessor
   */
  void postGetOp(final ObserverContext<RegionCoprocessorEnvironment> c, final Get get,
      final List<Cell> result)
    throws IOException;

  /**
   * WARNING: please override postGetOp instead of this method.  This is to maintain some
   * compatibility and to ease the transition from 0.94 -> 0.96.
   */
  @Deprecated
  void postGet(final ObserverContext<RegionCoprocessorEnvironment> c, final Get get,
      final List<KeyValue> result)
    throws IOException;

  /**
   * Called before the client tests for existence using a Get.
   * <p>
   * Call CoprocessorEnvironment#bypass to skip default actions
   * <p>
   * Call CoprocessorEnvironment#complete to skip any subsequent chained
   * coprocessors
   * @param c the environment provided by the region server
   * @param get the Get request
   * @param exists
   * @return the value to return to the client if bypassing default processing
   * @throws IOException if an error occurred on the coprocessor
   */
  boolean preExists(final ObserverContext<RegionCoprocessorEnvironment> c, final Get get,
      final boolean exists)
    throws IOException;

  /**
   * Called after the client tests for existence using a Get.
   * <p>
   * Call CoprocessorEnvironment#complete to skip any subsequent chained
   * coprocessors
   * @param c the environment provided by the region server
   * @param get the Get request
   * @param exists the result returned by the region server
   * @return the result to return to the client
   * @throws IOException if an error occurred on the coprocessor
   */
  boolean postExists(final ObserverContext<RegionCoprocessorEnvironment> c, final Get get,
      final boolean exists)
    throws IOException;

  /**
   * Called before the client stores a value.
   * <p>
   * Call CoprocessorEnvironment#bypass to skip default actions
   * <p>
   * Call CoprocessorEnvironment#complete to skip any subsequent chained
   * coprocessors
   * @param c the environment provided by the region server
   * @param put The Put object
   * @param edit The WALEdit object that will be written to the wal
   * @param durability Persistence guarantee for this Put
   * @throws IOException if an error occurred on the coprocessor
   */
  void prePut(final ObserverContext<RegionCoprocessorEnvironment> c, 
      final Put put, final WALEdit edit, final Durability durability)
    throws IOException;

  /**
   * Called after the client stores a value.
   * <p>
   * Call CoprocessorEnvironment#complete to skip any subsequent chained
   * coprocessors
   * @param c the environment provided by the region server
   * @param put The Put object
   * @param edit The WALEdit object for the wal
   * @param durability Persistence guarantee for this Put
   * @throws IOException if an error occurred on the coprocessor
   */
  void postPut(final ObserverContext<RegionCoprocessorEnvironment> c, 
      final Put put, final WALEdit edit, final Durability durability)
    throws IOException;

  /**
   * Called before the client deletes a value.
   * <p>
   * Call CoprocessorEnvironment#bypass to skip default actions
   * <p>
   * Call CoprocessorEnvironment#complete to skip any subsequent chained
   * coprocessors
   * @param c the environment provided by the region server
   * @param delete The Delete object
   * @param edit The WALEdit object for the wal
   * @param durability Persistence guarantee for this Delete
   * @throws IOException if an error occurred on the coprocessor
   */
  void preDelete(final ObserverContext<RegionCoprocessorEnvironment> c, 
      final Delete delete, final WALEdit edit, final Durability durability)
    throws IOException;

  /**
   * Called after the client deletes a value.
   * <p>
   * Call CoprocessorEnvironment#complete to skip any subsequent chained
   * coprocessors
   * @param c the environment provided by the region server
   * @param delete The Delete object
   * @param edit The WALEdit object for the wal
   * @param durability Persistence guarantee for this Delete
   * @throws IOException if an error occurred on the coprocessor
   */
  void postDelete(final ObserverContext<RegionCoprocessorEnvironment> c,
      final Delete delete, final WALEdit edit, final Durability durability)
    throws IOException;
  
  /**
   * This will be called for every batch mutation operation happening at the server. This will be
   * called after acquiring the locks on the mutating rows and after applying the proper timestamp
   * for each Mutation at the server. The batch may contain Put/Delete. By setting OperationStatus
   * of Mutations ({@link MiniBatchOperationInProgress#setOperationStatus(int, OperationStatus)}),
   * {@link RegionObserver} can make HRegion to skip these Mutations.
   * @param c the environment provided by the region server
   * @param miniBatchOp batch of Mutations getting applied to region.
   * @throws IOException if an error occurred on the coprocessor
   */
  void preBatchMutate(final ObserverContext<RegionCoprocessorEnvironment> c,
      final MiniBatchOperationInProgress<Mutation> miniBatchOp) throws IOException;

  /**
   * This will be called after applying a batch of Mutations on a region. The Mutations are added to
   * memstore and WAL.
   * @param c the environment provided by the region server
   * @param miniBatchOp batch of Mutations applied to region.
   * @throws IOException if an error occurred on the coprocessor
   */
  void postBatchMutate(final ObserverContext<RegionCoprocessorEnvironment> c,
      final MiniBatchOperationInProgress<Mutation> miniBatchOp) throws IOException;

  /**
   * Called before checkAndPut
   * <p>
   * Call CoprocessorEnvironment#bypass to skip default actions
   * <p>
   * Call CoprocessorEnvironment#complete to skip any subsequent chained
   * coprocessors
   * @param c the environment provided by the region server
   * @param row row to check
   * @param family column family
   * @param qualifier column qualifier
   * @param compareOp the comparison operation
   * @param comparator the comparator
   * @param put data to put if check succeeds
   * @param result 
   * @return the return value to return to client if bypassing default
   * processing
   * @throws IOException if an error occurred on the coprocessor
   */
  boolean preCheckAndPut(final ObserverContext<RegionCoprocessorEnvironment> c,
      final byte [] row, final byte [] family, final byte [] qualifier,
      final CompareOp compareOp, final ByteArrayComparable comparator,
      final Put put, final boolean result)
    throws IOException;

  /**
   * Called after checkAndPut
   * <p>
   * Call CoprocessorEnvironment#complete to skip any subsequent chained
   * coprocessors
   * @param c the environment provided by the region server
   * @param row row to check
   * @param family column family
   * @param qualifier column qualifier
   * @param compareOp the comparison operation
   * @param comparator the comparator
   * @param put data to put if check succeeds
   * @param result from the checkAndPut
   * @return the possibly transformed return value to return to client
   * @throws IOException if an error occurred on the coprocessor
   */
  boolean postCheckAndPut(final ObserverContext<RegionCoprocessorEnvironment> c,
      final byte [] row, final byte [] family, final byte [] qualifier,
      final CompareOp compareOp, final ByteArrayComparable comparator,
      final Put put, final boolean result)
    throws IOException;

  /**
   * Called before checkAndDelete
   * <p>
   * Call CoprocessorEnvironment#bypass to skip default actions
   * <p>
   * Call CoprocessorEnvironment#complete to skip any subsequent chained
   * coprocessors
   * @param c the environment provided by the region server
   * @param row row to check
   * @param family column family
   * @param qualifier column qualifier
   * @param compareOp the comparison operation
   * @param comparator the comparator
   * @param delete delete to commit if check succeeds
   * @param result 
   * @return the value to return to client if bypassing default processing
   * @throws IOException if an error occurred on the coprocessor
   */
  boolean preCheckAndDelete(final ObserverContext<RegionCoprocessorEnvironment> c,
      final byte [] row, final byte [] family, final byte [] qualifier,
      final CompareOp compareOp, final ByteArrayComparable comparator,
      final Delete delete, final boolean result)
    throws IOException;

  /**
   * Called after checkAndDelete
   * <p>
   * Call CoprocessorEnvironment#complete to skip any subsequent chained
   * coprocessors
   * @param c the environment provided by the region server
   * @param row row to check
   * @param family column family
   * @param qualifier column qualifier
   * @param compareOp the comparison operation
   * @param comparator the comparator
   * @param delete delete to commit if check succeeds
   * @param result from the CheckAndDelete
   * @return the possibly transformed returned value to return to client
   * @throws IOException if an error occurred on the coprocessor
   */
  boolean postCheckAndDelete(final ObserverContext<RegionCoprocessorEnvironment> c,
      final byte [] row, final byte [] family, final byte [] qualifier,
      final CompareOp compareOp, final ByteArrayComparable comparator,
      final Delete delete, final boolean result)
    throws IOException;

  /**
   * Called before incrementColumnValue
   * <p>
   * Call CoprocessorEnvironment#bypass to skip default actions
   * <p>
   * Call CoprocessorEnvironment#complete to skip any subsequent chained
   * coprocessors
   * @param c the environment provided by the region server
   * @param row row to check
   * @param family column family
   * @param qualifier column qualifier
   * @param amount long amount to increment
   * @param writeToWAL true if the change should be written to the WAL
   * @return value to return to the client if bypassing default processing
   * @throws IOException if an error occurred on the coprocessor
   * @deprecated This hook is no longer called by the RegionServer
   */
  @Deprecated
  long preIncrementColumnValue(final ObserverContext<RegionCoprocessorEnvironment> c,
      final byte [] row, final byte [] family, final byte [] qualifier,
      final long amount, final boolean writeToWAL)
    throws IOException;

  /**
   * Called after incrementColumnValue
   * <p>
   * Call CoprocessorEnvironment#complete to skip any subsequent chained
   * coprocessors
   * @param c the environment provided by the region server
   * @param row row to check
   * @param family column family
   * @param qualifier column qualifier
   * @param amount long amount to increment
   * @param writeToWAL true if the change should be written to the WAL
   * @param result the result returned by incrementColumnValue
   * @return the result to return to the client
   * @throws IOException if an error occurred on the coprocessor
   * @deprecated This hook is no longer called by the RegionServer
   */
  @Deprecated
  long postIncrementColumnValue(final ObserverContext<RegionCoprocessorEnvironment> c,
      final byte [] row, final byte [] family, final byte [] qualifier,
      final long amount, final boolean writeToWAL, final long result)
    throws IOException;

  /**
   * Called before Append
   * <p>
   * Call CoprocessorEnvironment#bypass to skip default actions
   * <p>
   * Call CoprocessorEnvironment#complete to skip any subsequent chained
   * coprocessors
   * @param c the environment provided by the region server
   * @param append Append object
   * @return result to return to the client if bypassing default processing
   * @throws IOException if an error occurred on the coprocessor
   */
  Result preAppend(final ObserverContext<RegionCoprocessorEnvironment> c,
      final Append append)
    throws IOException;

  /**
   * Called after Append
   * <p>
   * Call CoprocessorEnvironment#complete to skip any subsequent chained
   * coprocessors
   * @param c the environment provided by the region server
   * @param append Append object
   * @param result the result returned by increment
   * @return the result to return to the client
   * @throws IOException if an error occurred on the coprocessor
   */
  Result postAppend(final ObserverContext<RegionCoprocessorEnvironment> c,
      final Append append, final Result result)
    throws IOException;

  /**
   * Called before Increment
   * <p>
   * Call CoprocessorEnvironment#bypass to skip default actions
   * <p>
   * Call CoprocessorEnvironment#complete to skip any subsequent chained
   * coprocessors
   * @param c the environment provided by the region server
   * @param increment increment object
   * @return result to return to the client if bypassing default processing
   * @throws IOException if an error occurred on the coprocessor
   */
  Result preIncrement(final ObserverContext<RegionCoprocessorEnvironment> c,
      final Increment increment)
    throws IOException;

  /**
   * Called after increment
   * <p>
   * Call CoprocessorEnvironment#complete to skip any subsequent chained
   * coprocessors
   * @param c the environment provided by the region server
   * @param increment increment object
   * @param result the result returned by increment
   * @return the result to return to the client
   * @throws IOException if an error occurred on the coprocessor
   */
  Result postIncrement(final ObserverContext<RegionCoprocessorEnvironment> c,
      final Increment increment, final Result result)
    throws IOException;

  /**
   * Called before the client opens a new scanner.
   * <p>
   * Call CoprocessorEnvironment#bypass to skip default actions
   * <p>
   * Call CoprocessorEnvironment#complete to skip any subsequent chained
   * coprocessors
   * @param c the environment provided by the region server
   * @param scan the Scan specification
   * @param s if not null, the base scanner
   * @return an RegionScanner instance to use instead of the base scanner if
   * overriding default behavior, null otherwise
   * @throws IOException if an error occurred on the coprocessor
   */
  RegionScanner preScannerOpen(final ObserverContext<RegionCoprocessorEnvironment> c,
      final Scan scan, final RegionScanner s)
    throws IOException;

  /**
   * Called before a store opens a new scanner.
   * This hook is called when a "user" scanner is opened.
   * <p>
   * See {@link #preFlushScannerOpen(ObserverContext, Store, KeyValueScanner, InternalScanner)}
   * and {@link #preCompactScannerOpen(ObserverContext,
   *  Store, List, ScanType, long, InternalScanner)}
   * to override scanners created for flushes or compactions, resp.
   * <p>
   * Call CoprocessorEnvironment#complete to skip any subsequent chained
   * coprocessors.
   * Calling {@link org.apache.hadoop.hbase.coprocessor.ObserverContext#bypass()} has no
   * effect in this hook.
   * @param c the environment provided by the region server
   * @param store the store being scanned
   * @param scan the Scan specification
   * @param targetCols columns to be used in the scanner
   * @param s the base scanner, if not {@code null}, from previous RegionObserver in the chain
   * @return a KeyValueScanner instance to use or {@code null} to use the default implementation
   * @throws IOException if an error occurred on the coprocessor
   */
  KeyValueScanner preStoreScannerOpen(final ObserverContext<RegionCoprocessorEnvironment> c,
      final Store store, final Scan scan, final NavigableSet<byte[]> targetCols,
      final KeyValueScanner s) throws IOException;

  /**
   * Called after the client opens a new scanner.
   * <p>
   * Call CoprocessorEnvironment#complete to skip any subsequent chained
   * coprocessors
   * @param c the environment provided by the region server
   * @param scan the Scan specification
   * @param s if not null, the base scanner
   * @return the scanner instance to use
   * @throws IOException if an error occurred on the coprocessor
   */
  RegionScanner postScannerOpen(final ObserverContext<RegionCoprocessorEnvironment> c,
      final Scan scan, final RegionScanner s)
    throws IOException;

  /**
   * Called before the client asks for the next row on a scanner.
   * <p>
   * Call CoprocessorEnvironment#bypass to skip default actions
   * <p>
   * Call CoprocessorEnvironment#complete to skip any subsequent chained
   * coprocessors
   * @param c the environment provided by the region server
   * @param s the scanner
   * @param result The result to return to the client if default processing
   * is bypassed. Can be modified. Will not be returned if default processing
   * is not bypassed.
   * @param limit the maximum number of results to return
   * @param hasNext the 'has more' indication
   * @return 'has more' indication that should be sent to client
   * @throws IOException if an error occurred on the coprocessor
   */
  boolean preScannerNext(final ObserverContext<RegionCoprocessorEnvironment> c,
      final InternalScanner s, final List<Result> result,
      final int limit, final boolean hasNext)
    throws IOException;

  /**
   * Called after the client asks for the next row on a scanner.
   * <p>
   * Call CoprocessorEnvironment#complete to skip any subsequent chained
   * coprocessors
   * @param c the environment provided by the region server
   * @param s the scanner
   * @param result the result to return to the client, can be modified
   * @param limit the maximum number of results to return
   * @param hasNext the 'has more' indication
   * @return 'has more' indication that should be sent to client
   * @throws IOException if an error occurred on the coprocessor
   */
  boolean postScannerNext(final ObserverContext<RegionCoprocessorEnvironment> c,
      final InternalScanner s, final List<Result> result, final int limit,
      final boolean hasNext)
    throws IOException;

  /**
   * This will be called by the scan flow when the current scanned row is being filtered out by the
   * filter. The filter may be filtering out the row via any of the below scenarios
   * <ol>
   * <li>
   * <code>boolean filterRowKey(byte [] buffer, int offset, int length)</code> returning true</li>
   * <li>
   * <code>boolean filterRow()</code> returning true</li>
   * <li>
   * <code>void filterRow(List<KeyValue> kvs)</code> removing all the kvs from the passed List</li>
   * </ol>
   * @param c the environment provided by the region server
   * @param s the scanner
   * @param currentRow The current rowkey which got filtered out
   * @param hasMore the 'has more' indication
   * @return whether more rows are available for the scanner or not
   * @throws IOException
   */
  boolean postScannerFilterRow(final ObserverContext<RegionCoprocessorEnvironment> c,
      final InternalScanner s, final byte[] currentRow, final boolean hasMore) throws IOException;
  
  /**
   * Called before the client closes a scanner.
   * <p>
   * Call CoprocessorEnvironment#bypass to skip default actions
   * <p>
   * Call CoprocessorEnvironment#complete to skip any subsequent chained
   * coprocessors
   * @param c the environment provided by the region server
   * @param s the scanner
   * @throws IOException if an error occurred on the coprocessor
   */
  void preScannerClose(final ObserverContext<RegionCoprocessorEnvironment> c,
      final InternalScanner s)
    throws IOException;

  /**
   * Called after the client closes a scanner.
   * <p>
   * Call CoprocessorEnvironment#complete to skip any subsequent chained
   * coprocessors
   * @param c the environment provided by the region server
   * @param s the scanner
   * @throws IOException if an error occurred on the coprocessor
   */
  void postScannerClose(final ObserverContext<RegionCoprocessorEnvironment> c,
      final InternalScanner s)
    throws IOException;

  /**
   * Called before a {@link org.apache.hadoop.hbase.regionserver.wal.WALEdit}
   * replayed for this region.
   *
   * @param ctx
   * @param info
   * @param logKey
   * @param logEdit
   * @throws IOException
   */
  void preWALRestore(final ObserverContext<RegionCoprocessorEnvironment> ctx,
      HRegionInfo info, HLogKey logKey, WALEdit logEdit) throws IOException;

  /**
   * Called after a {@link org.apache.hadoop.hbase.regionserver.wal.WALEdit}
   * replayed for this region.
   *
   * @param ctx
   * @param info
   * @param logKey
   * @param logEdit
   * @throws IOException
   */
  void postWALRestore(final ObserverContext<RegionCoprocessorEnvironment> ctx,
      HRegionInfo info, HLogKey logKey, WALEdit logEdit) throws IOException;

  /**
   * Called before bulkLoadHFile. Users can create a StoreFile instance to
   * access the contents of a HFile.
   *
   * @param ctx
   * @param familyPaths pairs of { CF, HFile path } submitted for bulk load. Adding
   * or removing from this list will add or remove HFiles to be bulk loaded.
   * @throws IOException
   */
  void preBulkLoadHFile(final ObserverContext<RegionCoprocessorEnvironment> ctx,
    List<Pair<byte[], String>> familyPaths) throws IOException;

  /**
   * Called after bulkLoadHFile.
   *
   * @param ctx
   * @param familyPaths pairs of { CF, HFile path } submitted for bulk load
   * @param hasLoaded whether the bulkLoad was successful
   * @return the new value of hasLoaded
   * @throws IOException
   */
  boolean postBulkLoadHFile(final ObserverContext<RegionCoprocessorEnvironment> ctx,
    List<Pair<byte[], String>> familyPaths, boolean hasLoaded) throws IOException;
}
