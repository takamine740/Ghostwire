package com.ghostwire.llm;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.Socket;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * Trusts the JVM CA store and, on Windows, the Windows certificate store.
 * Corporate TLS inspection (for example Netskope) installs its root only in Windows,
 * so the JVM store alone rejects Bedrock.
 */
public final class TlsSupport {

    private static volatile SSLContext context;

    private TlsSupport() {
    }

    public static SSLContext context(ClientLog log) throws Exception {
        SSLContext existing = context;
        if (existing != null) {
            return existing;
        }
        synchronized (TlsSupport.class) {
            if (context == null) {
                context = build(log);
            }
            return context;
        }
    }

    private static SSLContext build(ClientLog log) throws Exception {
        List<X509ExtendedTrustManager> managers = new ArrayList<>();
        managers.add(asExtended(defaultTrustManager()));
        String stores = "JVM CAs";
        try {
            TrustManagerFactory windows = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            KeyStore windowsRoots = KeyStore.getInstance("Windows-ROOT");
            windowsRoots.load(null, null);
            windows.init(windowsRoots);
            X509ExtendedTrustManager win = firstExtended(windows.getTrustManagers());
            if (win != null) {
                managers.add(win);
                stores = "JVM CAs + Windows";
            }
        } catch (Exception e) {
            log.info("Ghostwire: Windows certificate store is not available (" + e.getMessage() + "). Using the JVM CA store only.");
        }
        log.info("Ghostwire: TLS trust stores: " + stores);
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(null, new TrustManager[]{new CompositeTrustManager(managers)}, null);
        return ssl;
    }

    private static X509TrustManager defaultTrustManager() throws Exception {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init((KeyStore) null);
        for (TrustManager manager : factory.getTrustManagers()) {
            if (manager instanceof X509TrustManager x509) {
                return x509;
            }
        }
        throw new IllegalStateException("No JVM X509 trust manager");
    }

    private static X509ExtendedTrustManager firstExtended(TrustManager[] managers) {
        for (TrustManager manager : managers) {
            X509ExtendedTrustManager extended = asExtended(manager);
            if (extended != null) {
                return extended;
            }
        }
        return null;
    }

    private static X509ExtendedTrustManager asExtended(TrustManager manager) {
        if (manager instanceof X509ExtendedTrustManager extended) {
            return extended;
        }
        if (manager instanceof X509TrustManager base) {
            return new X509ExtendedTrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    base.checkClientTrusted(chain, authType);
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    base.checkServerTrusted(chain, authType);
                }

                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
                    base.checkClientTrusted(chain, authType);
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
                    base.checkServerTrusted(chain, authType);
                }

                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
                    base.checkClientTrusted(chain, authType);
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
                    base.checkServerTrusted(chain, authType);
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return base.getAcceptedIssuers();
                }
            };
        }
        return null;
    }

    private static final class CompositeTrustManager extends X509ExtendedTrustManager {
        private final List<X509ExtendedTrustManager> managers;

        private CompositeTrustManager(List<X509ExtendedTrustManager> managers) {
            this.managers = List.copyOf(managers);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            delegate(tm -> tm.checkClientTrusted(chain, authType));
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            delegate(tm -> tm.checkServerTrusted(chain, authType));
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            delegate(tm -> tm.checkClientTrusted(chain, authType, socket));
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            delegate(tm -> tm.checkServerTrusted(chain, authType, socket));
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            delegate(tm -> tm.checkClientTrusted(chain, authType, engine));
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            delegate(tm -> tm.checkServerTrusted(chain, authType, engine));
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            List<X509Certificate> issuers = new ArrayList<>();
            for (X509ExtendedTrustManager manager : managers) {
                X509Certificate[] accepted = manager.getAcceptedIssuers();
                if (accepted != null) {
                    for (X509Certificate certificate : accepted) {
                        issuers.add(certificate);
                    }
                }
            }
            return issuers.toArray(X509Certificate[]::new);
        }

        private void delegate(Attempt attempt) throws CertificateException {
            CertificateException first = null;
            for (X509ExtendedTrustManager manager : managers) {
                try {
                    attempt.run(manager);
                    return;
                } catch (CertificateException e) {
                    if (first == null) {
                        first = e;
                    }
                }
            }
            throw first == null ? new CertificateException("no trust managers") : first;
        }
    }

    @FunctionalInterface
    private interface Attempt {
        void run(X509ExtendedTrustManager manager) throws CertificateException;
    }
}
