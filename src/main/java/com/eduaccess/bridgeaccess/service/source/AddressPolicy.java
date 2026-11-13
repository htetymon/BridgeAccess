package com.eduaccess.bridgeaccess.service.source;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * Which IP addresses the link reader may connect to. Only public unicast addresses are
 * allowed: loopback, private, link-local (including cloud metadata at 169.254.169.254),
 * carrier-grade NAT, multicast, reserved and documentation ranges are blocked, as are IPv6
 * forms that embed a blocked IPv4 address (IPv4-compatible, NAT64, 6to4) and Teredo.
 */
@Component
public class AddressPolicy {

	private static final List<Cidr> BLOCKED = Arrays.stream(new String[] {
			// IPv4
			"0.0.0.0/8", // "this network"
			"10.0.0.0/8", // private
			"100.64.0.0/10", // carrier-grade NAT
			"127.0.0.0/8", // loopback
			"169.254.0.0/16", // link-local, cloud metadata
			"172.16.0.0/12", // private
			"192.0.0.0/24", // IETF protocol assignments
			"192.0.2.0/24", // documentation
			"192.88.99.0/24", // 6to4 relay anycast
			"192.168.0.0/16", // private
			"198.18.0.0/15", // benchmarking
			"198.51.100.0/24", // documentation
			"203.0.113.0/24", // documentation
			"224.0.0.0/4", // multicast
			"240.0.0.0/4", // reserved, broadcast
			// IPv6
			"::/96", // unspecified, loopback, deprecated IPv4-compatible
			"64:ff9b:1::/48", // local-use NAT64
			"100::/64", // discard
			"2001::/32", // Teredo
			"2001:db8::/32", // documentation
			"fc00::/7", // unique local
			"fe80::/10", // link-local
			"fec0::/10", // site-local (deprecated)
			"ff00::/8", // multicast
	}).map(Cidr::parse).toList();

	private static final Cidr NAT64 = Cidr.parse("64:ff9b::/96");

	private static final Cidr SIX_TO_FOUR = Cidr.parse("2002::/16");

	public boolean isBlocked(InetAddress address) {
		if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
				|| address.isSiteLocalAddress() || address.isMulticastAddress()) {
			return true;
		}
		byte[] bytes = address.getAddress();
		if (BLOCKED.stream().anyMatch(cidr -> cidr.matches(bytes))) {
			return true;
		}
		if (address instanceof Inet6Address) {
			// Java already turns ::ffff:a.b.c.d into an Inet4Address. Other IPv6 forms that
			// route to an embedded IPv4 address are judged by that address.
			if (NAT64.matches(bytes)) {
				return isBlocked(ipv4(bytes, 12));
			}
			if (SIX_TO_FOUR.matches(bytes)) {
				return isBlocked(ipv4(bytes, 2));
			}
		}
		return false;
	}

	private static InetAddress ipv4(byte[] bytes, int offset) {
		try {
			return Inet4Address.getByAddress(Arrays.copyOfRange(bytes, offset, offset + 4));
		}
		catch (UnknownHostException ex) {
			throw new IllegalStateException(ex); // 4 bytes is always a valid address
		}
	}

	private record Cidr(byte[] network, int prefix) {

		static Cidr parse(String cidr) {
			int slash = cidr.indexOf('/');
			try {
				// Literals only, so this never touches DNS.
				byte[] network = InetAddress.getByName(cidr.substring(0, slash)).getAddress();
				return new Cidr(network, Integer.parseInt(cidr.substring(slash + 1)));
			}
			catch (UnknownHostException ex) {
				throw new IllegalArgumentException(cidr, ex);
			}
		}

		boolean matches(byte[] address) {
			if (address.length != this.network.length) {
				return false;
			}
			int full = this.prefix / 8;
			for (int i = 0; i < full; i++) {
				if (address[i] != this.network[i]) {
					return false;
				}
			}
			int rest = this.prefix % 8;
			if (rest == 0) {
				return true;
			}
			int mask = (0xFF << (8 - rest)) & 0xFF;
			return (address[full] & mask) == (this.network[full] & mask);
		}

	}

}
