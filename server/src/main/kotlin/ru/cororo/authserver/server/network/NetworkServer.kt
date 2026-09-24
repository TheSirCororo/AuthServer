package ru.cororo.authserver.server.network

import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.timeout.ReadTimeoutHandler
import ru.cororo.authserver.server.AuthServerImpl
import ru.cororo.authserver.server.network.handler.HandshakeHandler
import ru.cororo.authserver.server.network.pipeline.FrameDecoder
import ru.cororo.authserver.server.network.pipeline.FrameEncoder
import ru.cororo.authserver.server.network.pipeline.PacketCodec
import java.util.concurrent.TimeUnit

class NetworkServer(private val server: AuthServerImpl) {
    private val bossGroup = MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())
    private val workerGroup = MultiThreadIoEventLoopGroup(NioIoHandler.newFactory())
    private var channel: Channel? = null

    /** Binds and returns once the socket is listening; the port actually bound is available as [port]. */
    fun bind(host: String, port: Int) {
        channel = ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel::class.java)
            .childOption(ChannelOption.TCP_NODELAY, true)
            .childHandler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(channel: SocketChannel) {
                    val connection = Connection(channel)
                    connection.handler = HandshakeHandler(server, connection)
                    channel.pipeline()
                        .addLast("timeout", ReadTimeoutHandler(server.config.network.readTimeoutSeconds.toLong(), TimeUnit.SECONDS))
                        .addLast(Connection.FRAME_DECODER, FrameDecoder())
                        .addLast(Connection.FRAME_ENCODER, FrameEncoder())
                        .addLast(Connection.PACKET_CODEC, PacketCodec(connection))
                        .addLast(Connection.HANDLER, connection)
                }
            })
            .bind(host, port).awaitUninterruptibly().let { future ->
                future.cause()?.let { throw IllegalStateException("Cannot listen on $host:$port: ${it.message}", it) }
                future.channel()
            }
    }

    val port: Int get() = (channel?.localAddress() as? java.net.InetSocketAddress)?.port ?: -1

    fun close() {
        channel?.close()?.syncUninterruptibly()
        bossGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly()
        workerGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly()
    }
}
