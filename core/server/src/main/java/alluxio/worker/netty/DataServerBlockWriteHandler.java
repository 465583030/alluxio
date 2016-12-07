/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.worker.netty;

import alluxio.StorageTierAssoc;
import alluxio.WorkerStorageTierAssoc;
import alluxio.network.protocol.RPCProtoMessage;
import alluxio.proto.dataserver.Protocol;
import alluxio.worker.block.BlockWorker;
import alluxio.worker.block.io.BlockWriter;

import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCountUtil;

import java.io.IOException;
import java.nio.channels.GatheringByteChannel;
import java.util.concurrent.ExecutorService;

import javax.annotation.concurrent.NotThreadSafe;

/**
 * This handler handles block write request. Check more information in
 * {@link DataServerWriteHandler}.
 */
@NotThreadSafe
public final class DataServerBlockWriteHandler extends DataServerWriteHandler {
  /** The Block Worker which handles blocks stored in the Alluxio storage of the worker. */
  private final BlockWorker mWorker;
  /** An object storing the mapping of tier aliases to ordinals. */
  private final StorageTierAssoc mStorageTierAssoc = new WorkerStorageTierAssoc();;

  private class BlockWriteRequestInternal extends WriteRequestInternal {
    public BlockWriter mBlockWriter;

    public BlockWriteRequestInternal(Protocol.WriteRequest request) throws Exception {
      mBlockWriter = mWorker.getTempBlockWriterRemote(request.getSessionId(), request.getId());
      mSessionId = request.getSessionId();
      mId = request.getId();
    }

    @Override
    public void close() throws IOException {
      mBlockWriter.close();
    }
  }

  /**
   * Creates an instance of {@link DataServerBlockWriteHandler}.
   *
   * @param executorService the executor service to run {@link PacketWriter}s.
   */
  public DataServerBlockWriteHandler(ExecutorService executorService, BlockWorker blockWorker) {
    super(executorService);
    mWorker = blockWorker;
  }

  /**
   * Initializes the handler if necessary.
   *
   * @param msg the block write request
   * @throws Exception if it fails to initialize
   */
  protected void initializeRequest(RPCProtoMessage msg) throws Exception {
    super.initializeRequest(msg);
    if (mRequest == null) {
      Protocol.WriteRequest request = (Protocol.WriteRequest) (msg.getMessage());
      mRequest = new BlockWriteRequestInternal(request);
    }
  }

  protected void writeBuf(ByteBuf buf) throws Exception {
    try {
      if (mPosToWrite == 0) {
        // This is the first write to the block, so create the temp block file. The file will only
        // be created if the first write starts at offset 0. This allocates enough space for the
        // write.
        mWorker.createBlockRemote(mRequest.mSessionId, mRequest.mId, mStorageTierAssoc.getAlias(0),
            buf.readableBytes());
      } else {
        // Allocate enough space in the existing temporary block for the write.
        mWorker.requestSpace(mRequest.mSessionId, mRequest.mId, buf.readableBytes());
      }
      BlockWriter blockWriter = ((BlockWriteRequestInternal) mRequest).mBlockWriter;
      GatheringByteChannel outputChannel = blockWriter.getChannel();
      buf.readBytes(outputChannel, buf.readableBytes());
    } finally {
      ReferenceCountUtil.release(buf);
    }
  }
}
