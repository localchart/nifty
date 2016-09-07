/*
 * Copyright (C) 2012-2013 Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.nifty.ssl;

import com.google.common.io.BaseEncoding;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;

import static java.util.Objects.requireNonNull;

/**
 * Crypto-related utilities.
 */
class CryptoUtil {

    /** Length of a SHA256 hash, in bytes */
    static final int SHA256_OUTPUT_BYTES = 256 / 8;

    /** Maximum value for the outputLength parameter to hkdf(). */
    static final int MAX_HKDF_OUTPUT_LENGTH = SHA256_OUTPUT_BYTES * 255;

    /** Default all-0 salt used by HKDF if a null salt parameter is provided. */
    private static final byte[] NULL_SALT = new byte[SHA256_OUTPUT_BYTES];

    /** Empty byte array. */
    private static final byte[] EMPTY_BYTES = new byte[0];

    /** Name of the HMAC algorithm used by hkdf() function. */
    private static final String MAC_ALGORITHM = "HmacSHA256";

    /**
     * Initializes a {@link Mac} object using the given key.
     *
     * @param key the HMAC key.
     * @return the initialized Mac object.
     * @throws IllegalArgumentException if the provided key is invalid.
     */
    private static Mac initHmacSha256(byte[] key) {
        try {
            SecretKeySpec keySpec = new SecretKeySpec(key, MAC_ALGORITHM);
            Mac mac = Mac.getInstance(MAC_ALGORITHM);
            mac.init(keySpec);
            return mac;
        }
        catch (GeneralSecurityException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /**
     * The "extract" stage of the extract-then-expand HKDF algorithm.
     *
     * @param salt an optional salt value. If null, will use the all-zeros constant NULL_SALT.
     * @param inputKeyingMaterial input keying material. Must not be null.
     * @return a pseudo-random key of length SHA256_OUTPUT_BYTES to be used by the expand() stage.
     */
    private static byte[] extract(byte[] salt, byte[] inputKeyingMaterial) {
        return initHmacSha256(salt == null ? NULL_SALT : salt).doFinal(inputKeyingMaterial);
    }

    /**
     * The "expand" stage of the extract-then-expand HKDF algorithm. All arguments are required (may not be null).
     *
     * @param prk the pseudo-random key generated by the extract() stage.
     * @param previousRoundResult the output of the previous expand() round. For the first round, it should be a
     *                            zero-length byte array.
     * @param info the info parameter to hkdf().
     * @param counter the counter for the current expand stage.
     * @return output keying material of length SHA256_OUTPUT_BYTES.
     */
    private static byte[] expand(byte[] prk, byte[] previousRoundResult, byte[] info, byte counter) {
        Mac mac = initHmacSha256(prk);
        mac.update(previousRoundResult);
        mac.update(info);
        mac.update(counter);
        return mac.doFinal();
    }

    /**
     * The HKDF key-derivation function, as defined in RFC 5869 (see https://tools.ietf.org/html/rfc5869).
     *
     * @param inputKeyingMaterial the input keying material to HKDF. May not be null.
     * @param salt optional salt parameter, may be null. This value does not need to be secret and can be safely
     *             reused between different calls to hkdf(). See the RFC for recommended practices for salt
     *             selection.
     * @param info optional application- and/or context-specific information used to bind the derived key material
     *             to a particular context or use case. See the RFC for recommended practices for info parameter
     *             selection.
     * @param outputLength desired length of the output key, in bytes. May not exceed MAX_HKDF_OUTPUT_LENGTH.
     * @return a pseudo-random key of outputLength bytes long.
     * @throws IllegalArgumentException if outputLength > MAX_HKDF_OUTPUT_LENGTH.
     */
    static byte[] hkdf(byte[] inputKeyingMaterial, byte[] salt, byte[] info, int outputLength) {
        if (outputLength > MAX_HKDF_OUTPUT_LENGTH) {
            throw new IllegalArgumentException("Output length too large " + outputLength);
        }

        byte[] prk = extract(salt, inputKeyingMaterial);

        int numRounds = (int) Math.ceil((double) outputLength / SHA256_OUTPUT_BYTES);
        byte[] outputData = new byte[outputLength];

        int idx = 0;
        byte[] current = EMPTY_BYTES;
        if (info == null) {
            info = EMPTY_BYTES;
        }

        for (int i = 0; i < numRounds; ++i) {
            current = expand(prk, current, info, (byte) (i + 1));
            System.arraycopy(current, 0, outputData, idx, Math.min(SHA256_OUTPUT_BYTES, outputLength - idx));
            idx += SHA256_OUTPUT_BYTES;
        }
        return outputData;
    }

    /**
     * Decodes a hex-encoded string into a byte array. Accepts both lower- and upper-case [a-f] characters.
     *
     * @param hex the hexadecimal string to decode.
     * @return the input string converted to a binary byte array.
     * @throws IllegalArgumentException if the input string contains any non-hex characters, or if the length of
     *                                  the input string is not a multiple of 2.
     */
    static byte[] decodeHex(String hex) {
        requireNonNull(hex);
        return BaseEncoding.base16().decode(hex.toUpperCase());
    }
}