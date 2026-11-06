package eu.amadeos.apps.call.protocol;

import eu.amadeos.apps.call.domain.PeerKey;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;

/**
 * TLS 1.3 between two amade-call programs. Both sides show their own self-signed certificate (an Ed25519 key,
 * see {@code IdentityStore}), so there is no certificate authority: TLS checks that the other side owns the key
 * it shows, and amade-call checks <em>which</em> key that is ({@link #peerKey}): the one announced in the
 * network, the one known from earlier calls, and, once compared, the one behind the safety code.
 */
public final class Tls {
    public static final String PROTOCOL = "TLSv1.3";

    private Tls() {
    }

    public static SSLContext context(KeyStore identity, char[] password) throws GeneralSecurityException {
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(identity, password);
        SSLContext context = SSLContext.getInstance(PROTOCOL);
        context.init(keys.getKeyManagers(), new TrustManager[]{new AnyKey()}, null);
        return context;
    }

    /** The key the other side proved to own in the handshake. */
    public static PeerKey peerKey(SSLSession session) throws SSLPeerUnverifiedException {
        Certificate[] chain = session.getPeerCertificates();
        if (chain.length == 0) throw new SSLPeerUnverifiedException("no certificate");
        return PeerKey.of(chain[0].getPublicKey());
    }

    /**
     * Accepts any certificate that is well-formed and signed by its own key: who the other side is gets decided
     * by its key, not by a certificate authority.
     */
    private static final class AnyKey implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws java.security.cert.CertificateException {
            check(chain);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws java.security.cert.CertificateException {
            check(chain);
        }

        private static void check(X509Certificate[] chain) throws java.security.cert.CertificateException {
            if (chain == null || chain.length == 0) throw new java.security.cert.CertificateException("no certificate");
            try {
                chain[0].verify(chain[0].getPublicKey());
            } catch (GeneralSecurityException e) {
                throw new java.security.cert.CertificateException("certificate not signed by its own key", e);
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
