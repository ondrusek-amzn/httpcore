/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */

package org.apache.hc.core5.http2.hpack;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.apache.hc.core5.annotation.NotThreadSafe;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.util.Args;
import org.apache.hc.core5.util.ByteArrayBuffer;
import org.apache.hc.core5.util.LangUtils;

@NotThreadSafe
public final class HPackEncoder {

    private final OutboundDynamicTable dynamicTable;
    private final ByteArrayBuffer huffmanBuf;
    private final CharsetEncoder charsetEncoder;
    private ByteBuffer tmpBuf;
    private int maxTableSize;

    HPackEncoder(final OutboundDynamicTable dynamicTable, final Charset charset) {
        Args.notNull(charset, "Charset");
        this.dynamicTable = dynamicTable != null ? dynamicTable : new OutboundDynamicTable();
        this.huffmanBuf = new ByteArrayBuffer(128);
        this.charsetEncoder = charset.equals(StandardCharsets.US_ASCII) || charset.equals(StandardCharsets.ISO_8859_1) ? null : charset.newEncoder();
    }

    public HPackEncoder(final Charset charset) {
        this(new OutboundDynamicTable(), charset);
    }

    static void encodeInt(final ByteArrayBuffer dst, final int n, final int i, final int mask) {

        final int nbits = 0xFF >>> (8 - n);
        int value = i;
        if (value < nbits) {
            dst.append(i | mask);
        } else {
            dst.append(nbits | mask);
            value -= nbits;

            while (value >= 0x80) {
                dst.append((value & 0x7F) | 0x80);
                value >>>= 7;
            }
            dst.append(value);
        }
    }

    static void encodeHuffman(final ByteArrayBuffer dst, final ByteBuffer src) throws HPackException {

        Huffman.ENCODER.encode(dst, src);
    }

    void encodeString(final ByteArrayBuffer dst, final ByteBuffer src, final boolean huffman) throws HPackException {

        final int strLen = src.remaining();
        if (huffman) {
            this.huffmanBuf.clear();
            this.huffmanBuf.ensureCapacity(strLen);
            Huffman.ENCODER.encode(this.huffmanBuf, src);
            dst.ensureCapacity(this.huffmanBuf.length() + 8);
            encodeInt(dst, 7, this.huffmanBuf.length(), 0x80);
            dst.append(this.huffmanBuf.buffer(), 0, this.huffmanBuf.length());
        } else {
            dst.ensureCapacity(strLen + 8);
            encodeInt(dst, 7, strLen, 0x0);
            if (src.hasArray()) {
                final byte[] b = src.array();
                final int off = src.position();
                dst.append(b, off, strLen);
                src.position(off + strLen);
            } else {
                while (src.hasRemaining()) {
                    dst.append(src.get());
                }
            }
        }
    }

    private void clearState() {

        if (this.tmpBuf != null) {
            this.tmpBuf.clear();
        }
        if (this.charsetEncoder != null) {
            this.charsetEncoder.reset();
        }
    }

    private void expandCapacity(final int capacity) {

        final ByteBuffer previous = this.tmpBuf;
        this.tmpBuf = ByteBuffer.allocate(capacity);
        previous.flip();
        this.tmpBuf.put(previous);
    }

    private void ensureCapacity(final int extra) {

        if (this.tmpBuf == null) {
            this.tmpBuf = ByteBuffer.allocate(Math.max(256, extra));
        }
        final int requiredCapacity = this.tmpBuf.remaining() + extra;
        if (requiredCapacity > this.tmpBuf.capacity()) {
            expandCapacity(requiredCapacity);
        }
    }

    int encodeString(
            final ByteArrayBuffer dst,
            final CharSequence charSequence, final int off, final int len,
            final boolean huffman) throws HPackException, CharacterCodingException {

        clearState();
        if (this.charsetEncoder == null) {
            if (huffman) {
                this.huffmanBuf.clear();
                this.huffmanBuf.ensureCapacity(len);
                Huffman.ENCODER.encode(this.huffmanBuf, charSequence, off, len);
                dst.ensureCapacity(this.huffmanBuf.length() + 8);
                encodeInt(dst, 7, this.huffmanBuf.length(), 0x80);
                dst.append(this.huffmanBuf.buffer(), 0, this.huffmanBuf.length());
            } else {
                dst.ensureCapacity(len + 8);
                encodeInt(dst, 7, len, 0x0);
                for (int i = 0; i < len; i++) {
                    dst.append((int) charSequence.charAt(off + i));
                }
            }
            return len;
        } else {
            final CharBuffer in = CharBuffer.wrap(charSequence, off, len);
            while (in.hasRemaining()) {
                ensureCapacity((int) (in.remaining() * this.charsetEncoder.averageBytesPerChar()) + 8);
                final CoderResult result = this.charsetEncoder.encode(in, this.tmpBuf, true);
                if (result.isError()) {
                    result.throwException();
                }
            }
            ensureCapacity(8);
            final CoderResult result = this.charsetEncoder.flush(this.tmpBuf);
            if (result.isError()) {
                result.throwException();
            }
            this.tmpBuf.flip();
            final int binaryLen = this.tmpBuf.remaining();
            encodeString(dst, this.tmpBuf, huffman);
            return binaryLen;
        }
    }

    int encodeString(final ByteArrayBuffer dst, final String s, final boolean huffman) throws HPackException, CharacterCodingException {

        return encodeString(dst, s, 0, s.length(), huffman);
    }

    void encodeLiteralHeader(
            final ByteArrayBuffer dst, final HPackEntry existing, final Header header,
            final HPackRepresentation representation, final boolean useHuffman) throws CharacterCodingException, HPackException {

        final int n;
        final int mask;
        switch (representation) {
            case WITH_INDEXING:
                mask = 0x40;
                n = 6;
                break;
            case WITHOUT_INDEXING:
                mask = 0x00;
                n = 4;
                break;
            case NEVER_INDEXED:
                mask = 0x10;
                n = 4;
                break;
            default:
                throw new IllegalStateException("Unexpected value: " + representation);
        }
        final int index = existing != null ? existing.getIndex() : 0;
        final int nameLen;
        if (index <= 0) {
            encodeInt(dst, n, 0, mask);
            nameLen = encodeString(dst, header.getName(), useHuffman);
        } else {
            encodeInt(dst, n, index, mask);
            nameLen = existing.getHeader().getNameLen();
        }
        final int valueLen = encodeString(dst, header.getValue(), useHuffman);
        if (representation == HPackRepresentation.WITH_INDEXING) {
            dynamicTable.add(new HPackHeader(header.getName(), nameLen, header.getValue(), valueLen, header.isSensitive()));
        }
    }

    void encodeIndex(final ByteArrayBuffer dst, final int index) throws HPackException {
        encodeInt(dst, 7, index, 0x80);
    }

    private int findFullMatch(final List<HPackEntry> entries, final String value) {
        if (entries == null || entries.isEmpty()) {
            return 0;
        }
        for (int i = 0; i < entries.size(); i++) {
            final HPackEntry entry = entries.get(i);
            if (LangUtils.equals(value, entry.getHeader().getValue())) {
                return entry.getIndex();
            }
        }
        return 0;
    }

    void encodeHeader(
            final ByteArrayBuffer dst, final Header header,
            final boolean noIndexing, final boolean useHuffman) throws CharacterCodingException, HPackException {

        final HPackRepresentation representation;
        if (header.isSensitive()) {
            representation = HPackRepresentation.NEVER_INDEXED;
        } else if (noIndexing) {
            representation = HPackRepresentation.WITHOUT_INDEXING;
        } else {
            representation = HPackRepresentation.WITH_INDEXING;
        }

        final String key = header.getName();
        final String value = header.getValue();

        final List<HPackEntry> staticEntries = StaticTable.INSTANCE.getByName(key);

        if (representation == HPackRepresentation.WITH_INDEXING) {
            // Try to find full match and encode as as index
            final int staticIndex = findFullMatch(staticEntries, value);
            if (staticIndex > 0) {
                encodeIndex(dst, staticIndex);
                return;
            }
            final List<HPackEntry> dynamicEntries = dynamicTable.getByName(key);
            final int dynamicIndex = findFullMatch(dynamicEntries, value);
            if (dynamicIndex > 0) {
                encodeIndex(dst, dynamicIndex);
                return;
            }
        }
        // Encode as literal
        HPackEntry existing = null;
        if (staticEntries != null && !staticEntries.isEmpty()) {
            existing = staticEntries.get(0);
        } else {
            final List<HPackEntry> dynamicEntries = dynamicTable.getByName(key);
            if (dynamicEntries != null && !dynamicEntries.isEmpty()) {
                existing = dynamicEntries.get(0);
            }
        }
        encodeLiteralHeader(dst, existing, header, representation, useHuffman);
    }

    void encodeHeaders(
            final ByteArrayBuffer dst, final List<Header> headers,
            final boolean noIndexing, final boolean useHuffman) throws CharacterCodingException, HPackException {
        for (int i = 0; i < headers.size(); i++) {
            encodeHeader(dst, headers.get(i), noIndexing, useHuffman);
        }
    }

    public void encodeHeader(
            final ByteArrayBuffer dst, final Header header) throws CharacterCodingException, HPackException {
        encodeHeader(dst, header, false, true);
    }

    public void encodeHeaders(
            final ByteArrayBuffer dst, final List<Header> headers) throws CharacterCodingException, HPackException {
        encodeHeaders(dst, headers, false, true);
    }

    public int getMaxTableSize() {
        return this.maxTableSize;
    }

    public void setMaxTableSize(final int maxTableSize) {
        Args.notNegative(maxTableSize, "Max table size");
        this.maxTableSize = maxTableSize;
        this.dynamicTable.setMaxSize(maxTableSize);
    }

}
