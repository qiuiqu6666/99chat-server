/*
 * Decompiled with CFR 0.152.
 */
package com.chat99.server.favorite;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class FavoriteMediaFetcher {
    private final OkHttpClient http = new OkHttpClient.Builder().connectTimeout(15L, TimeUnit.SECONDS).readTimeout(300L, TimeUnit.SECONDS).followRedirects(true).dns(FavoriteMediaFetcher::resolvePublicAddresses).build();

    public byte[] fetch(String url, long maxBytes) {
        byte[] byArray;
        block14: {
            if (url == null || url.isBlank()) {
                throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "EMPTY_CONTENT");
            }
            String safeUrl = FavoriteMediaFetcher.validateUrl(url);
            Request req = new Request.Builder().url(safeUrl).get().build();
            Response resp = this.http.newCall(req).execute();
            try {
                if (!resp.isSuccessful() || resp.body() == null) {
                    throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_GATEWAY, "MEDIA_FETCH_FAILED");
                }
                long len = resp.body().contentLength();
                if (len > maxBytes) {
                    throw new ResponseStatusException((HttpStatusCode)HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE");
                }
                byte[] data = resp.body().bytes();
                if ((long)data.length > maxBytes) {
                    throw new ResponseStatusException((HttpStatusCode)HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE");
                }
                if (data.length == 0) {
                    throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "EMPTY_CONTENT");
                }
                byArray = data;
                if (resp == null) break block14;
            }
            catch (Throwable throwable) {
                try {
                    if (resp != null) {
                        try {
                            resp.close();
                        }
                        catch (Throwable throwable2) {
                            throwable.addSuppressed(throwable2);
                        }
                    }
                    throw throwable;
                }
                catch (ResponseStatusException e) {
                    throw e;
                }
                catch (IOException e) {
                    throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_GATEWAY, "MEDIA_FETCH_FAILED");
                }
            }
            resp.close();
        }
        return byArray;
    }

    private static String validateUrl(String raw) {
        try {
            URI uri = URI.create(raw.trim());
            String scheme = uri.getScheme();
            if (scheme == null || !"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme) || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException("unsupported URL");
            }
            return uri.toASCIIString();
        }
        catch (IllegalArgumentException e) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "INVALID_MEDIA_URL");
        }
    }

    private static List<InetAddress> resolvePublicAddresses(String hostname) throws UnknownHostException {
        List<InetAddress> addresses = Arrays.asList(InetAddress.getAllByName(hostname));
        if (addresses.isEmpty() || addresses.stream().anyMatch(FavoriteMediaFetcher::isBlockedAddress)) {
            throw new UnknownHostException("private or unsafe address");
        }
        return addresses;
    }

    private static boolean isBlockedAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            return first == 100 && second >= 64 && second <= 127;
        }
        return bytes.length == 16 && (bytes[0] & 0xFE) == 252;
    }
}
