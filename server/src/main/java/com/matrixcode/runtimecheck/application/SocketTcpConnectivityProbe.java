package com.matrixcode.runtimecheck.application;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;

@Component
public class SocketTcpConnectivityProbe implements TcpConnectivityProbe {

    @Override
    public boolean canConnect(String host, int port, Duration timeout) {
        if (host == null || host.isBlank() || port <= 0) {
            return false;
        }
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(host.trim(), port), Math.toIntExact(timeout.toMillis()));
            return true;
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }
}
