package com.eduaccess.bridgeaccess.service.source;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;

/**
 * Resolves host names for the HTTP client and refuses the whole host if any of its addresses
 * is blocked. Because the client connects only to addresses returned here, a host that
 * passes a check and then re-resolves to an internal address (DNS rebinding) is still caught.
 */
final class GuardedDnsResolver implements DnsResolver {

	private final AddressPolicy policy;

	private final DnsResolver delegate;

	GuardedDnsResolver(AddressPolicy policy) {
		this(policy, SystemDefaultDnsResolver.INSTANCE);
	}

	GuardedDnsResolver(AddressPolicy policy, DnsResolver delegate) {
		this.policy = policy;
		this.delegate = delegate;
	}

	@Override
	public InetAddress[] resolve(String host) throws UnknownHostException {
		InetAddress[] addresses = this.delegate.resolve(host);
		for (InetAddress address : addresses) {
			if (this.policy.isBlocked(address)) {
				throw new BlockedAddressException(host, address);
			}
		}
		return addresses;
	}

	@Override
	public List<InetSocketAddress> resolve(String host, int port) throws UnknownHostException {
		return Arrays.stream(resolve(host)).map(address -> new InetSocketAddress(address, port)).toList();
	}

	@Override
	public String resolveCanonicalHostname(String host) throws UnknownHostException {
		return this.delegate.resolveCanonicalHostname(host);
	}

	/** An {@link UnknownHostException} so the HTTP client fails the connection like any lookup error. */
	static final class BlockedAddressException extends UnknownHostException {

		BlockedAddressException(String host, InetAddress address) {
			super(host + " resolves to blocked address " + address.getHostAddress());
		}

	}

}
