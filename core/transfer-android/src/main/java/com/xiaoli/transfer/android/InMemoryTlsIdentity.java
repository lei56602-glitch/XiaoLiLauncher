package com.xiaoli.transfer.android;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Date;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.net.ssl.*;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.*;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * Stable per-install RSA-2048 TLS identity.
 * The RSA private key is encrypted at rest with an AndroidKeyStore AES-GCM key
 * and is only reconstructed in memory when the app needs its TLS identity.
 */
final class InMemoryTlsIdentity {
    private static final String ALIAS = "QuanLian-RSA-2048-MEMORY";
    private static final String AES_ALIAS = "quanlian_tls_identity_aes_v1";
    private static final String IDENTITY_FILE = "tls_identity_v1.bin";
    private static final byte[] MAGIC = new byte[]{'Q','L','I','1'};
    private static final Provider BC = new org.bouncycastle.jce.provider.BouncyCastleProvider();
    private static volatile InMemoryTlsIdentity INSTANCE;

    final KeyPair keyPair;
    final X509Certificate certificate;
    final X509ExtendedKeyManager keyManager;

    private InMemoryTlsIdentity(Context context) throws Exception {
        File file = new File(context.getNoBackupFilesDir(), IDENTITY_FILE);
        if (file.isFile()) {
            byte[] plain = decrypt(context, readAll(file));
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(plain))) {
                int keyLength = in.readInt();
                if (keyLength < 1 || keyLength > 16384) throw new IOException("Invalid private key length");
                byte[] keyBytes = new byte[keyLength]; in.readFully(keyBytes);
                int certLength = in.readInt();
                if (certLength < 1 || certLength > 65536) throw new IOException("Invalid certificate length");
                byte[] certBytes = new byte[certLength]; in.readFully(certBytes);
                if (in.available() != 0) throw new IOException("Unexpected identity data");
                PrivateKey privateKey = KeyFactory.getInstance("RSA").generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(keyBytes));
                X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(certBytes));
                cert.checkValidity(new Date());
                cert.verify(cert.getPublicKey());
                verifyPair(privateKey, cert.getPublicKey());
                keyPair = new KeyPair(cert.getPublicKey(), privateKey);
                certificate = cert;
            }
            android.util.Log.i("QuanLianTLS", "STABLE_IDENTITY_RESTORED source=noBackupFilesDir");
        } else {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048, new SecureRandom());
            KeyPair pair = generator.generateKeyPair();
            long now = System.currentTimeMillis();
            Date notBefore = new Date(now - 5L * 60L * 1000L);
            Date notAfter = new Date(now + 365L * 24L * 60L * 60L * 1000L);
            X500Name subject = new X500Name("CN=QuanLian Device");
            BigInteger serial = new BigInteger(159, new SecureRandom()).add(BigInteger.ONE);
            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(subject, serial, notBefore, notAfter, subject, pair.getPublic());
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
            builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
            builder.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
            ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").setProvider(BC).build(pair.getPrivate());
            X509CertificateHolder holder = builder.build(signer);
            X509Certificate cert = new JcaX509CertificateConverter().setProvider(BC).getCertificate(holder);
            cert.checkValidity(new Date()); cert.verify(pair.getPublic());
            keyPair = pair; certificate = cert;
            verifyPair(pair.getPrivate(), cert.getPublicKey());
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                byte[] key = pair.getPrivate().getEncoded(), encodedCert = cert.getEncoded();
                out.writeInt(key.length); out.write(key); out.writeInt(encodedCert.length); out.write(encodedCert);
            }
            writeAtomically(file, encrypt(context, bytes.toByteArray()));
            android.util.Log.i("QuanLianTLS", "STABLE_IDENTITY_CREATED source=noBackupFilesDir");
        }

        keyManager = new X509ExtendedKeyManager() {
            @Override public String[] getClientAliases(String keyType, Principal[] issuers) { return null; }
            @Override public String chooseClientAlias(String[] keyType, Principal[] issuers, java.net.Socket socket) { return null; }
            @Override public String[] getServerAliases(String keyType, Principal[] issuers) { return isRsa(keyType) ? new String[]{ALIAS} : null; }
            @Override public String chooseServerAlias(String keyType, Principal[] issuers, java.net.Socket socket) { return isRsa(keyType) ? ALIAS : null; }
            @Override public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) { return isRsa(keyType) ? ALIAS : null; }
            @Override public X509Certificate[] getCertificateChain(String alias) { return ALIAS.equals(alias) ? new X509Certificate[]{certificate} : null; }
            @Override public PrivateKey getPrivateKey(String alias) { return ALIAS.equals(alias) ? keyPair.getPrivate() : null; }
            private boolean isRsa(String keyType) { return keyType != null && keyType.toUpperCase(java.util.Locale.ROOT).contains("RSA"); }
        };
        android.util.Log.i("QuanLianTLS", "IDENTITY_READY keyAlgorithm=RSA,keyBits=2048,certificateSignature="
            + certificate.getSigAlgName() + ",subject=" + certificate.getSubjectX500Principal());
    }

    private static byte[] readAll(File file) throws IOException {
        if (file.length() < 32 || file.length() > 131072) throw new IOException("Invalid TLS identity file size");
        try (FileInputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }

    private static SecretKey getOrCreateAesKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        java.security.Key existing = store.getKey(AES_ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(AES_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }

    private static byte[] encrypt(Context context, byte[] plain) throws Exception {
        SecretKey key = getOrCreateAesKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] iv = cipher.getIV(), encrypted = cipher.doFinal(plain);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(MAGIC); bytes.write(iv.length); bytes.write(iv); bytes.write(encrypted);
        return bytes.toByteArray();
    }

    private static byte[] decrypt(Context context, byte[] envelope) throws Exception {
        if (envelope.length < 4 + 1 + 12 + 16 || envelope[0] != MAGIC[0] || envelope[1] != MAGIC[1]
            || envelope[2] != MAGIC[2] || envelope[3] != MAGIC[3]) throw new IOException("Unknown TLS identity format");
        int ivLength = envelope[4] & 255;
        if (ivLength != 12 || envelope.length <= 5 + ivLength + 16) throw new IOException("Invalid TLS identity IV");
        byte[] iv = java.util.Arrays.copyOfRange(envelope, 5, 5 + ivLength);
        byte[] encrypted = java.util.Arrays.copyOfRange(envelope, 5 + ivLength, envelope.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateAesKey(), new GCMParameterSpec(128, iv));
        return cipher.doFinal(encrypted);
    }

    private static void writeAtomically(File file, byte[] bytes) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("Cannot create identity directory");
        File temp = new File(parent, file.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) { out.write(bytes); out.getFD().sync(); }
        if (!temp.renameTo(file)) { if (file.exists() && !file.delete()) throw new IOException("Cannot replace identity file"); if (!temp.renameTo(file)) throw new IOException("Cannot commit identity file"); }
    }

    private static void verifyPair(PrivateKey privateKey, PublicKey publicKey) throws Exception {
        byte[] challenge = "QuanLian TLS identity consistency check".getBytes(StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(privateKey); signer.update(challenge); byte[] signature = signer.sign();
        signer.initVerify(publicKey); signer.update(challenge);
        if (!signer.verify(signature)) throw new GeneralSecurityException("TLS private key does not match certificate");
    }

    static InMemoryTlsIdentity get(Context context) throws Exception {
        InMemoryTlsIdentity value = INSTANCE;
        if (value == null) {
            synchronized (InMemoryTlsIdentity.class) {
                value = INSTANCE;
                if (value == null) INSTANCE = value = new InMemoryTlsIdentity(context.getApplicationContext());
            }
        }
        return value;
    }
}
