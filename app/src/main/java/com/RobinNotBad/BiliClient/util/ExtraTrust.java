package com.RobinNotBad.BiliClient.util;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.RobinNotBad.BiliClient.R;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import okhttp3.OkHttpClient;

/**
 * 「系统根证书 + 应用内置根证书」合并信任（26.10.26）。
 *
 * <p><b>为什么要有它</b>：内测码更新走的分享站（见 {@link BetaUpdate#HOSTS}）用的是 Let's Encrypt 证书。
 * Let's Encrypt 的根 ISRG Root X1 直到 <b>Android 7.1.1（API 25）</b>才进系统证书库；以前老设备靠
 * 「DST Root CA X3 交叉签名」兜底，但 2024 年 Let's Encrypt 已停发这条交叉链。于是 Android 7.1 及以下
 * （大量手表还停在 4.4~7.1）访问分享站会报
 * {@code CertPathValidatorException: Trust anchor for certification path not found}，
 * 而走 DigiCert/Sectigo 的 Gitee、GitHub 更新源不受影响——这就是「部分设备」才出问题的原因。
 *
 * <p><b>做法</b>：把 {@code res/raw/extra_ca_roots.pem} 里的根证书<b>追加</b>到系统信任锚之上，构造一个普通的 {@link X509TrustManager}。证书链、有效期、主机名照常校验，
 * <b>不是</b> trust-all；只是让老设备也认识这两张主流根证书。
 *
 * <p><b>内置了哪些根</b>（PEM 文件里故意不写注释：老系统的证书解析器不一定容忍 PEM 块外的文字）：
 * <ul>
 *   <li>ISRG Root X1，有效期至 2035-06-04，SHA-256
 *       {@code 96BCEC06264976F37460779ACF28C5A7CFE8A3C0AAE11A8FFCEE05C0BDDF08C6}</li>
 *   <li>ISRG Root X2，有效期至 2040-09-17，SHA-256
 *       {@code 69729B8E15A86EFC177A57AFB7171DFC64ADD28C2FCA8CF1507E34453CCB1470}</li>
 * </ul>
 * 分享站以后要是换了别家的证书，往这个文件里追加对应的根证书 PEM 就行，代码不用改。
 *
 * <p><b>为什么合并信任锚，而不是「先系统、失败再内置」的组合 TrustManager</b>：API 24+ 默认 TrustManager
 * 是网络安全配置的 RootTrustManager，本应用的配置里有 domain-config，它要求调用方走带主机名的
 * {@code X509ExtendedTrustManager} 接口；而那个类 API 24 才有，minSdk 19 上直接继承会类加载失败。
 * 用 {@link TrustManagerFactory#init(KeyStore)} 合并后得到的是平台标准实现，两种接口都支持。
 * 网络安全配置在本应用里只管明文白名单，OkHttp 判明文不依赖 TrustManager，所以不受影响。
 *
 * <p><b>作用范围</b>：只给 {@link UpdateManager} 自己的 OkHttpClient 用（更新检查 / 内测包 / 下载）。
 * 带登录 Cookie 的 B 站主客户端（{@link NetWorkUtil}）保持系统默认，不扩大信任面。
 *
 * <p>任何一步失败都返回 null，调用方原样用系统默认——宁可退回旧行为，也不能让更新整体不可用。
 */
public final class ExtraTrust {

    private static final String TAG = "ExtraTrust";

    private static volatile X509TrustManager cached;

    private ExtraTrust() {
    }

    /**
     * 给 builder 装上合并信任。构造失败时原样返回 builder（即系统默认校验）。
     */
    @NonNull
    public static OkHttpClient.Builder apply(@NonNull OkHttpClient.Builder builder, @Nullable Context context) {
        X509TrustManager tm = trustManager(context);
        if (tm == null) return builder;
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{tm}, null);
            return builder.sslSocketFactory(sslContext.getSocketFactory(), tm);
        } catch (GeneralSecurityException e) {
            Logu.e(TAG, "构造 SSLContext 失败，回落系统默认：" + e.getMessage());
            return builder;
        }
    }

    @Nullable
    private static X509TrustManager trustManager(@Nullable Context context) {
        X509TrustManager tm = cached;
        if (tm != null || context == null) return tm;
        synchronized (ExtraTrust.class) {
            if (cached != null) return cached;
            try (InputStream in = context.getResources().openRawResource(R.raw.extra_ca_roots)) {
                cached = build(systemTrustManager(), readCertificates(in));
            } catch (Exception e) {
                Logu.e(TAG, "加载内置根证书失败，回落系统默认：" + e.getMessage());
            }
            return cached;
        }
    }

    /** 系统默认 TrustManager（API 24+ 即网络安全配置的 base-config）。 */
    @NonNull
    static X509TrustManager systemTrustManager() throws GeneralSecurityException {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((KeyStore) null);
        return firstX509(tmf.getTrustManagers());
    }

    /** 读 PEM / DER，支持一个文件里串联多张证书。 */
    @NonNull
    static List<X509Certificate> readCertificates(@NonNull InputStream in) throws GeneralSecurityException {
        Collection<? extends Certificate> certs = CertificateFactory.getInstance("X.509").generateCertificates(in);
        List<X509Certificate> result = new ArrayList<>();
        for (Certificate c : certs) {
            if (c instanceof X509Certificate) result.add((X509Certificate) c);
        }
        if (result.isEmpty()) throw new GeneralSecurityException("内置证书文件里没有证书");
        return result;
    }

    /**
     * 纯逻辑部分（不碰 Context，可在 JVM 上直接验证）：
     * 系统 TrustManager 认可的全部根证书 + extra，合成一个新的 TrustManager。
     */
    @NonNull
    static X509TrustManager build(@NonNull X509TrustManager system, @NonNull List<X509Certificate> extra)
            throws GeneralSecurityException, IOException {
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        int i = 0;
        for (X509Certificate c : system.getAcceptedIssuers()) {
            ks.setCertificateEntry("sys-" + (i++), c);
        }
        for (X509Certificate c : extra) {
            ks.setCertificateEntry("extra-" + (i++), c);
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        return firstX509(tmf.getTrustManagers());
    }

    @NonNull
    private static X509TrustManager firstX509(TrustManager[] managers) throws GeneralSecurityException {
        for (TrustManager m : managers) {
            if (m instanceof X509TrustManager) return (X509TrustManager) m;
        }
        throw new GeneralSecurityException("没有可用的 X509TrustManager");
    }
}
