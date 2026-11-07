package eu.amadeos.apps.call.service;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * How others reach this computer by address: its name in the local network ({@code name.local}, which Avahi
 * answers) and its IPv4 addresses, for the status line and for people who call by address.
 */
public final class LocalAddress {
    private LocalAddress() {
    }

    /** For example {@code anna-pc.local, 192.168.1.20}; the name alone when no network is up. */
    public static String describe() {
        List<String> parts = new ArrayList<>();
        String host = hostName();
        if (!host.isEmpty()) parts.add(host + ".local");
        parts.addAll(addresses());
        return String.join(", ", parts);
    }

    /** The computer's name without a domain, in lower case, or empty. */
    static String hostName() {
        try {
            return shortName(InetAddress.getLocalHost().getHostName());
        } catch (java.io.IOException | SecurityException e) {
            return "";
        }
    }

    static String shortName(String name) {
        if (name == null || name.isBlank() || name.equals("localhost")) return "";
        int dot = name.indexOf('.');
        return (dot > 0 ? name.substring(0, dot) : name).toLowerCase(Locale.ROOT);
    }

    /** The IPv4 addresses of the network cards that are up, without loopback and bridges of virtual machines. */
    static List<String> addresses() {
        List<String> list = new ArrayList<>();
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback() || nic.isVirtual() || !real(nic.getName())) continue;
                for (InetAddress a : Collections.list(nic.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLinkLocalAddress()) list.add(a.getHostAddress());
                }
            }
        } catch (SocketException | RuntimeException e) {
            return List.of();
        }
        Collections.sort(list);
        return list.size() > 2 ? list.subList(0, 2) : list;
    }

    static boolean real(String name) {
        return !(name.startsWith("docker") || name.startsWith("virbr") || name.startsWith("br-")
                || name.startsWith("veth") || name.startsWith("lxc") || name.startsWith("vmnet"));
    }
}
