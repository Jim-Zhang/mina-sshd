/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sshd.common.util.io.der;

import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StreamCorruptedException;
import java.math.BigInteger;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.sshd.common.util.NumberUtils;
import org.apache.sshd.common.util.ValidateUtils;
import org.apache.sshd.common.util.buffer.BufferUtils;
import org.apache.sshd.common.util.buffer.ByteArrayBuffer;

/**
 * A bare-minimum DER encoder - just enough so we can encoder signatures
 * and keys data
 *
 * @author <a href="mailto:dev@mina.apache.org">Apache MINA SSHD Project</a>
 */
public class DERWriter extends FilterOutputStream {
    private final byte[] lenBytes = new byte[Integer.BYTES];

    public DERWriter() {
        this(ByteArrayBuffer.DEFAULT_SIZE);
    }

    public DERWriter(int initialSize) {
        this(new ByteArrayOutputStream(initialSize));
    }

    public DERWriter(OutputStream stream) {
        super(Objects.requireNonNull(stream, "No output stream"));
    }

    public DERWriter startSequence() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        AtomicBoolean dataWritten = new AtomicBoolean(false);
        @SuppressWarnings("resource")
        DERWriter encloser = this;
        return new DERWriter(baos) {
            @Override
            public void close() throws IOException {
                baos.close();

                if (!dataWritten.getAndSet(true)) { // detect repeated calls and write this only once
                    encloser.writeObject(new ASN1Object(ASN1Class.UNIVERSAL, ASN1Type.SEQUENCE, false, baos.size(), baos.toByteArray()));
                }
            }
        };
    }

    public void writeBigInteger(BigInteger value) throws IOException {
        writeBigInteger(Objects.requireNonNull(value, "No value").toByteArray());
    }

    public void writeBigInteger(byte... bytes) throws IOException {
        writeBigInteger(bytes, 0, NumberUtils.length(bytes));
    }

    public void writeBigInteger(byte[] bytes, int off, int len) throws IOException {
        // ASN.1 - zero padding if 1st byte is > 0x7F
        int padLen = ((len > 0) && ((bytes[off] & 0x80) != 0)) ? 1 : 0;

        write(0x02);    // indicate it is an INTEGER
        writeLength(len + padLen);
        for (int index = 0; index < padLen; index++) {
            write(0);
        }

        write(bytes, off, len);
    }


    public void writeObject(ASN1Object obj) throws IOException {
        Objects.requireNonNull(obj, "No ASN.1 object");

        ASN1Type type = obj.getObjType();
        byte typeValue = type.getTypeValue();
        ASN1Class clazz = obj.getObjClass();
        byte classValue = clazz.getClassValue();
        byte tagValue = (byte) (((classValue << 6) & 0xC0) | (typeValue & 0x1F));
        writeObject(tagValue, obj.getLength(), obj.getValue());
    }

    public void writeObject(byte tag, int len, byte... data) throws IOException {
        write(tag & 0xFF);
        writeLength(len);
        write(data, 0, len);
    }

    public void writeLength(int len) throws IOException {
        ValidateUtils.checkTrue(len >= 0, "Invalid length: %d", len);

        // short form - MSBit is zero
        if (len <= 127) {
            write(len);
            return;
        }

        BufferUtils.putUInt(len, lenBytes);

        int nonZeroPos = 0;
        for (; nonZeroPos < lenBytes.length; nonZeroPos++) {
            if (lenBytes[nonZeroPos] != 0) {
                break;
            }
        }

        if (nonZeroPos >= lenBytes.length) {
            throw new StreamCorruptedException("All zeroes length representation for len=" + len);
        }

        int bytesLen = lenBytes.length - nonZeroPos;
        write(0x80 | bytesLen); // indicate number of octets
        write(lenBytes, nonZeroPos, bytesLen);
    }

    public byte[] toByteArray() throws IOException {
        if (this.out instanceof ByteArrayOutputStream) {
            return ((ByteArrayOutputStream) this.out).toByteArray();
        } else {
            throw new IOException("The underlying stream is not a byte[] stream");
        }
    }
}
