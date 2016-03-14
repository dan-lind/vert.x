/*
 * Copyright (c) 2011-2013 The original author or authors
 *  ------------------------------------------------------
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  and Apache License v2.0 which accompanies this distribution.
 *
 *      The Eclipse Public License is available at
 *      http://www.eclipse.org/legal/epl-v10.html
 *
 *      The Apache License v2.0 is available at
 *      http://www.opensource.org/licenses/apache2.0.php
 *
 *  You may elect to redistribute this code under either of these licenses.
 */

package io.vertx.core.http.impl;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2ConnectionDecoder;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2Flags;
import io.netty.handler.codec.http2.Http2FrameListener;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.Http2Stream;
import io.netty.util.collection.IntObjectHashMap;
import io.netty.util.collection.IntObjectMap;
import io.vertx.codegen.annotations.Nullable;
import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.GoAway;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.impl.ContextImpl;
import io.vertx.core.net.NetSocket;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.Objects;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
abstract class VertxHttp2ConnectionHandler extends Http2ConnectionHandler implements Http2FrameListener, HttpConnection, Http2Connection.Listener {

  protected final IntObjectMap<VertxHttp2Stream> streams = new IntObjectHashMap<>();
  protected final ChannelHandlerContext handlerCtx;
  protected final Channel channel;
  protected final ContextImpl context;
  private Handler<Void> closeHandler;
  private boolean shuttingdown;
  private boolean shutdown;
  private Handler<io.vertx.core.http.Http2Settings> clientSettingsHandler;
  private Http2Settings clientSettings = new Http2Settings();
  private final ArrayDeque<Runnable> updateSettingsHandler = new ArrayDeque<>(4);
  private Http2Settings serverSettings = new Http2Settings();
  private Handler<GoAway> goAwayHandler;
  private Handler<Void> shutdownHandler;
  private Handler<Throwable> exceptionHandler;

  public VertxHttp2ConnectionHandler(
      ChannelHandlerContext handlerCtx, Channel channel, ContextImpl context,
      Http2ConnectionDecoder decoder, Http2ConnectionEncoder encoder, Http2Settings initialSettings) {
    super(decoder, encoder, initialSettings);

    connection().addListener(this);

    encoder.flowController().listener(stream -> {
      VertxHttp2Stream resp = streams.get(stream.id());
      resp.handleInterestedOpsChanged();
    });

    this.channel = channel;
    this.handlerCtx = handlerCtx;
    this.context = context;
  }

  NetSocket toNetSocket(VertxHttp2Stream stream) {
    VertxHttp2NetSocket rempl = new VertxHttp2NetSocket(stream.vertx, stream.context, stream.handlerContext, stream.encoder, stream.decoder, stream.stream);
    streams.put(stream.stream.id(), rempl);
    return rempl;
  }

  // Http2Connection.Listener

  @Override
  public void onStreamClosed(Http2Stream stream) {
    checkShutdownHandler();
    VertxHttp2Stream removed = streams.remove(stream.id());
    if (removed != null) {
      context.executeFromIO(() -> {
        removed.handleClose();
      });
    }
  }

  @Override
  public void onStreamAdded(Http2Stream stream) {
  }

  @Override
  public void onStreamActive(Http2Stream stream) {
  }

  @Override
  public void onStreamHalfClosed(Http2Stream stream) {
  }

  @Override
  public void onStreamRemoved(Http2Stream stream) {
  }

  @Override
  public void onPriorityTreeParentChanged(Http2Stream stream, Http2Stream oldParent) {
  }

  @Override
  public void onPriorityTreeParentChanging(Http2Stream stream, Http2Stream newParent) {
  }

  @Override
  public void onWeightChanged(Http2Stream stream, short oldWeight) {
  }

  @Override
  public void onGoAwaySent(int lastStreamId, long errorCode, ByteBuf debugData) {
  }

  @Override
  public void onGoAwayReceived(int lastStreamId, long errorCode, ByteBuf debugData) {
    Handler<GoAway> handler = goAwayHandler;
    if (handler != null) {
      Buffer buffer = Buffer.buffer(debugData);
      context.executeFromIO(() -> {
        handler.handle(new GoAway().setErrorCode(errorCode).setLastStreamId(lastStreamId).setDebugData(buffer));
      });
    }
    checkShutdownHandler();
  }

  // Http2FrameListener

  @Override
  public void onSettingsAckRead(ChannelHandlerContext ctx) {
    Runnable handler = updateSettingsHandler.poll();
    if (handler != null) {
      // No need to run on a particular context it shall be done by the handler already
      handler.run();
    }
  }

  @Override
  public void onSettingsRead(ChannelHandlerContext ctx, Http2Settings settings) {
    clientSettings.putAll(settings);
    if (clientSettingsHandler != null) {
      context.executeFromIO(() -> {
        clientSettingsHandler.handle(remoteSettings());
      });
    }
  }

  @Override
  public void onPingRead(ChannelHandlerContext ctx, ByteBuf data) {
  }

  @Override
  public void onPingAckRead(ChannelHandlerContext ctx, ByteBuf data) {
  }

  @Override
  public void onPushPromiseRead(ChannelHandlerContext ctx, int streamId, int promisedStreamId,
                                Http2Headers headers, int padding) throws Http2Exception {
  }

  @Override
  public void onGoAwayRead(ChannelHandlerContext ctx, int lastStreamId, long errorCode, ByteBuf debugData) {
  }

  @Override
  public void onWindowUpdateRead(ChannelHandlerContext ctx, int streamId, int windowSizeIncrement) {
  }

  @Override
  public void onUnknownFrame(ChannelHandlerContext ctx, byte frameType, int streamId,
                             Http2Flags flags, ByteBuf payload) {
  }

  @Override
  public void onRstStreamRead(ChannelHandlerContext ctx, int streamId, long errorCode) {
    VertxHttp2Stream req = streams.get(streamId);
    req.handleReset(errorCode);
  }

  @Override
  public int onDataRead(ChannelHandlerContext ctx, int streamId, ByteBuf data, int padding, boolean endOfStream) {
    VertxHttp2Stream req = streams.get(streamId);
    context.executeFromIO(() -> {
      req.handleData(Buffer.buffer(data.copy()));
    });
    if (endOfStream) {
      context.executeFromIO(req::handleEnd);
    }
    return padding;
  }

  // Http2Connection overrides

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
    super.exceptionCaught(ctx, cause);
    cause.printStackTrace();
    ctx.close();
  }


  @Override
  public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    super.channelInactive(ctx);
    if (closeHandler != null) {
      context.executeFromIO(() -> {
        closeHandler.handle(null);
      });
    }
  }

  @Override
  protected void onConnectionError(ChannelHandlerContext ctx, Throwable cause, Http2Exception http2Ex) {
    for (VertxHttp2Stream stream : streams.values()) {
      context.executeFromIO(() -> {
        stream.handleException(cause);
      });
    }
    Handler<Throwable> handler = exceptionHandler;
    if (handler != null) {
      context.executeFromIO(() -> {
        handler.handle(cause);
      });
    }
    // Default behavior send go away
    super.onConnectionError(ctx, cause, http2Ex);
  }

  @Override
  protected void onStreamError(ChannelHandlerContext ctx, Throwable cause, Http2Exception.StreamException http2Ex) {
    VertxHttp2Stream stream = streams.get(http2Ex.streamId());
    if (stream != null) {
      context.executeFromIO(() -> {
        stream.handleException(http2Ex);
      });
    }
    // Default behavior reset stream
    super.onStreamError(ctx, cause, http2Ex);
  }

  // HttpConnection implementation

  @Override
  public HttpConnection goAway(long errorCode, int lastStreamId, Buffer debugData) {
    if (errorCode < 0) {
      throw new IllegalArgumentException();
    }
    if (lastStreamId < 0) {
      throw new IllegalArgumentException();
    }
    encoder().writeGoAway(handlerCtx, lastStreamId, errorCode, debugData != null ? debugData.getByteBuf() : Unpooled.EMPTY_BUFFER, handlerCtx.newPromise());
    handlerCtx.flush();
    return this;
  }

  @Override
  public HttpConnection goAwayHandler(Handler<GoAway> handler) {
    goAwayHandler = handler;
    return this;
  }

  @Override
  public HttpConnection shutdownHandler(Handler<Void> handler) {
    shutdownHandler = handler;
    return this;
  }

  @Override
  public HttpConnection shutdown(long timeout) {
    if (timeout <= 0) {
      throw new IllegalArgumentException("Invalid timeout value " + timeout);
    }
    return shutdown((Long)timeout);
  }

  @Override
  public HttpConnection shutdown() {
    return shutdown(null);
  }

  private HttpConnection shutdown(Long timeout) {
    if (!shuttingdown) {
      shuttingdown = true;
      if (timeout != null) {
        gracefulShutdownTimeoutMillis(timeout);
      }
      channel.close();
    }
    return this;
  }

  @Override
  public HttpConnection closeHandler(Handler<Void> handler) {
    closeHandler = handler;
    return this;
  }

  @Override
  public void close() {
    shutdown((Long)0L);
  }

  @Override
  public HttpConnection remoteSettingsHandler(Handler<io.vertx.core.http.Http2Settings> handler) {
    clientSettingsHandler = handler;
    return this;
  }

  @Override
  public Handler<io.vertx.core.http.Http2Settings> remoteSettingsHandler() {
    return clientSettingsHandler;
  }

  @Override
  public io.vertx.core.http.Http2Settings remoteSettings() {
    io.vertx.core.http.Http2Settings a = new io.vertx.core.http.Http2Settings();
    a.setEnablePush(connection().remote().allowPushTo());
    a.setMaxConcurrentStreams((long)connection().local().maxActiveStreams());
    a.setMaxHeaderListSize(encoder().configuration().headerTable().maxHeaderListSize());
    a.setHeaderTableSize(encoder().configuration().headerTable().maxHeaderTableSize());
    a.setMaxFrameSize(encoder().configuration().frameSizePolicy().maxFrameSize());
    a.setInitialWindowSize(encoder().flowController().initialWindowSize());
    return a;
  }

  @Override
  public io.vertx.core.http.Http2Settings settings() {
    return HttpUtils.toVertxSettings(serverSettings);
  }

  @Override
  public HttpConnection updateSettings(io.vertx.core.http.Http2Settings settings) {
    return updateSettings(settings, null);
  }

  @Override
  public HttpConnection updateSettings(io.vertx.core.http.Http2Settings settings, @Nullable Handler<AsyncResult<Void>> completionHandler) {
    Http2Settings settingsUpdate = HttpUtils.fromVertxSettings(settings);
    updateSettings(settingsUpdate, completionHandler);
    return this;
  }

  protected void updateSettings(Http2Settings settingsUpdate, Handler<AsyncResult<Void>> completionHandler) {
    Context completionContext = completionHandler != null ? context.owner().getOrCreateContext() : null;
    Http2Settings current = decoder().localSettings();
    for (Map.Entry<Character, Long> entry : current.entrySet()) {
      Character key = entry.getKey();
      if (Objects.equals(settingsUpdate.get(key), entry.getValue())) {
        settingsUpdate.remove(key);
      }
    }

    encoder().writeSettings(handlerCtx, settingsUpdate, handlerCtx.newPromise()).addListener(fut -> {
      if (fut.isSuccess()) {
        updateSettingsHandler.add(() -> {
          serverSettings.putAll(settingsUpdate);
          if (completionHandler != null) {
            completionContext.runOnContext(v -> {
              completionHandler.handle(Future.succeededFuture());
            });
          }
        });
      } else {
        if (completionHandler != null) {
          completionContext.runOnContext(v -> {
            completionHandler.handle(Future.failedFuture(fut.cause()));
          });
        }
      }
    });
    handlerCtx.flush();
  }

  @Override
  public HttpConnection exceptionHandler(Handler<Throwable> handler) {
    exceptionHandler = handler;
    return this;
  }

  @Override
  public Handler<Throwable> exceptionHandler() {
    return exceptionHandler;
  }

  // Private

  private void checkShutdownHandler() {
    if (!shutdown) {
      Http2Connection conn = connection();
      if ((conn.goAwayReceived() || conn.goAwaySent()) && conn.numActiveStreams() == 0) {
        shutdown  = true;
        Handler<Void> handler = shutdownHandler;
        if (handler != null) {
          context.executeFromIO(() -> {
            shutdownHandler.handle(null);
          });
        }
      }
    }
  }

}