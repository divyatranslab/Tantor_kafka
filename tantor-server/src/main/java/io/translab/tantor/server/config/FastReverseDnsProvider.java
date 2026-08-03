package io.translab.tantor.server.config;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;
import java.util.stream.Stream;

/**
 * A custom DNS resolver provider that skips reverse DNS lookups.
 * This is crucial for air-gapped environments where Kafka's SASL client
 * triggers a blocking reverse DNS lookup (InetAddress.getHostName()) 
 * that times out after 10 seconds if no DNS/PTR records exist.
 */
public class FastReverseDnsProvider extends InetAddressResolverProvider {
    @Override
    public InetAddressResolver get(Configuration configuration) {
        return new FastReverseDnsResolver(configuration.builtinResolver());
    }

    @Override
    public String name() {
        return "FastReverseDnsProvider";
    }
}

class FastReverseDnsResolver implements InetAddressResolver {
    private final InetAddressResolver builtin;

    public FastReverseDnsResolver(InetAddressResolver builtin) {
        this.builtin = builtin;
    }

    @Override
    public Stream<InetAddress> lookupByName(String host, LookupPolicy lookupPolicy) throws UnknownHostException {
        // Delegate forward lookups to the built-in OS resolver
        return builtin.lookupByName(host, lookupPolicy);
    }

    @Override
    public String lookupByAddress(byte[] addr) throws UnknownHostException {
        // Instead of doing a reverse DNS lookup, immediately return the string IP address!
        try {
            return InetAddress.getByAddress(addr).getHostAddress();
        } catch (UnknownHostException e) {
            return builtin.lookupByAddress(addr);
        }
    }
}
