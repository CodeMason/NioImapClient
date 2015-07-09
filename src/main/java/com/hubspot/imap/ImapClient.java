package com.hubspot.imap;

import com.hubspot.imap.imap.command.BaseCommand;
import com.hubspot.imap.imap.command.BlankCommand;
import com.hubspot.imap.imap.command.Command;
import com.hubspot.imap.imap.command.CommandType;
import com.hubspot.imap.imap.command.ListCommand;
import com.hubspot.imap.imap.command.XOAuth2Command;
import com.hubspot.imap.imap.exceptions.AuthenticationFailedException;
import com.hubspot.imap.imap.response.ContinuationResponse;
import com.hubspot.imap.imap.response.ListResponse;
import com.hubspot.imap.imap.response.Response;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.concurrent.EventExecutorGroup;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.concurrent.Promise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class ImapClient extends ChannelDuplexHandler {
  private static final Logger LOGGER = LoggerFactory.getLogger(ImapClient.class);

  private final Channel channel;
  private final EventExecutorGroup executorGroup;
  private final String userName;
  private final String oauthToken;

  private final AtomicInteger commandCount;
  private final Promise<Void> loginPromise;

  private final AtomicReference<Command> currentCommand;
  private Promise lastCommandPromise;

  public ImapClient(Channel channel, EventExecutorGroup executorGroup, String userName, String oauthToken) {
    this.channel = channel;

    this.executorGroup = executorGroup;
    this.userName = userName;
    this.oauthToken = oauthToken;

    currentCommand = new AtomicReference<>();
    commandCount = new AtomicInteger(0);
    loginPromise = executorGroup.next().newPromise();

    this.channel.pipeline().addLast(new ImapCodec(this));
    this.channel.pipeline().addLast(executorGroup, this);
  }

  public Command getCurrentCommand() {
    return currentCommand.get();
  }

  public Future<Response> login() {
    Future<Response> loginFuture = send(new XOAuth2Command(userName, oauthToken, commandCount.getAndIncrement()));
    loginFuture.addListener(new FutureListener<Response>() {
      @Override
      public void operationComplete(Future<Response> future) throws Exception {
        Response response = future.get();
        if (response instanceof ContinuationResponse) {
          loginPromise.setFailure(new AuthenticationFailedException(response.getMessage()));
        } else {
          loginPromise.setSuccess(null);
        }
      }
    });

    loginPromise.addListener(future -> {
      if (!future.isSuccess()) {
        send(BlankCommand.INSTANCE);
      }
    });

    return loginFuture;
  }

  public Future<ListResponse> list(String context, String query) {
    return send(new ListCommand(commandCount.getAndIncrement(), context, query));
  }

  public Future<? extends Response> noop() {
    return send(CommandType.NOOP);
  }

  public boolean isLoggedIn() {
    return loginPromise.isSuccess() && channel.isOpen();
  }

  public void awaitLogin() throws InterruptedException, ExecutionException {
    loginPromise.get();
  }

  public Future<? extends Response> send(CommandType commandType, String... args) {
    BaseCommand baseCommand = new BaseCommand(commandType, commandCount.getAndIncrement(), args);
    return send(baseCommand);
  }

  public synchronized <T extends Response> Future<T> send(Command command) {
    final Promise<T> newPromise = executorGroup.next().newPromise();
    if (lastCommandPromise != null) {
      lastCommandPromise.awaitUninterruptibly();
    }
    executorGroup.submit(() -> {
      currentCommand.set(command);
      lastCommandPromise = newPromise;
      channel.writeAndFlush(command);
    });

    return newPromise;
  }

  @Override
  @SuppressWarnings("unchecked")
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    Response response = ((Response) msg);
    lastCommandPromise.setSuccess(response);
  }

  @Override
  public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
    if (evt instanceof IdleStateEvent) {
      noop();
    }

    super.userEventTriggered(ctx, evt);
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
    LOGGER.error("Error in handler", cause);
    super.exceptionCaught(ctx, cause);
  }

}
