/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.util;

import io.netty.logging.InternalLogger;
import io.netty.logging.InternalLoggerFactory;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Enumeration;

public final class SocketAddresses {

    public static final InetAddress LOCALHOST;
    public static final NetworkInterface LOOPBACK_IF;

    private static final InternalLogger logger =
            InternalLoggerFactory.getInstance(SocketAddresses.class);

    static {
        // We cache this because some machine takes almost forever to return
        // from InetAddress.getLocalHost().  I think it's due to the incorrect
        // /etc/hosts or /etc/resolve.conf.
        InetAddress localhost = null;
        try {
            localhost = InetAddress.getLocalHost();
        } catch (UnknownHostException e) {
            try {
                localhost = InetAddress.getByAddress(new byte[] { 127, 0, 0, 1 });
            } catch (UnknownHostException e1) {
                try {
                    localhost = InetAddress.getByAddress(
                            new byte[] { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1 });
                } catch (UnknownHostException e2) {
                    logger.error("Failed to resolve localhost", e2);
                }
            }
        }

        LOCALHOST = localhost;

        NetworkInterface loopbackIf;
        try {
            loopbackIf = NetworkInterface.getByInetAddress(LOCALHOST);
        } catch (SocketException e) {
            loopbackIf = null;
        }

        // If null is returned, iterate over all the available network interfaces.
        if (loopbackIf == null) {
            try {
                for (Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces();
                     e.hasMoreElements();) {
                    NetworkInterface nif = e.nextElement();
                    if (nif.isLoopback()) {
                        loopbackIf = nif;
                        break;
                    }
                }
            } catch (SocketException e) {
                logger.error("Failed to enumerate network interfaces", e);
            }
        }

        LOOPBACK_IF = loopbackIf;
    }

    private SocketAddresses() {
        // Unused
    }
}
