/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.redkale.convert.json;

import java.nio.ByteBuffer;
import org.redkale.util.*;

/**
 *
 * writeTo系列的方法输出的字符不能含特殊字符
 * <p>
 * 详情见: https://redkale.org
 *
 * @author zhangjx
 *
 * @since 2.3.0
 */
public class JsonCharsWriter extends JsonWriter {

    private static final char[] CHARS_TUREVALUE = "true".toCharArray();

    private static final char[] CHARS_FALSEVALUE = "false".toCharArray();

    private int count;

    private char[] content;

    public JsonCharsWriter() {
        this(defaultSize);
    }

    public JsonCharsWriter(int size) {
        this.content = new char[size > 1024 ? size : 1024];
    }

    //-----------------------------------------------------------------------
    /**
     * 返回指定至少指定长度的缓冲区
     *
     * @param len
     *
     * @return
     */
    private char[] expand(int len) {
        int newcount = count + len;
        if (newcount <= content.length) return content;
        char[] newdata = new char[Math.max(content.length * 3 / 2, newcount)];
        System.arraycopy(content, 0, newdata, 0, count);
        this.content = newdata;
        return newdata;
    }

    @Override
    public void writeTo(final char ch) { //只能是 0 - 127 的字符
        expand(1);
        content[count++] = ch;
    }

    @Override
    public void writeTo(final char[] chs, final int start, final int len) { //只能是 0 - 127 的字符
        expand(len);
        System.arraycopy(chs, start, content, count, len);
        count += len;
    }

    @Override
    public void writeTo(final byte ch) { //只能是 0 - 127 的字符
        expand(1);
        content[count++] = (char) ch;
    }

    @Override
    public void writeTo(final byte[] chs, final int start, final int len) { //只能是 0 - 127 的字符
        expand(len);
        for (int i = 0; i < len; i++) {
            content[count + i] = (char) chs[start + i];
        }
        count += len;
    }

    /**
     * <b>注意：</b> 该String值不能为null且不会进行转义， 只用于不含需要转义字符的字符串，例如enum、double、BigInteger转换的String
     *
     * @param quote 是否加双引号
     * @param value 非null且不含需要转义的字符的String值
     */
    @Override
    public void writeLatin1To(final boolean quote, final String value) {
        int len = value.length();
        expand(len + (quote ? 2 : 0));
        if (quote) content[count++] = '"';
        value.getChars(0, len, content, count);
        count += len;
        if (quote) content[count++] = '"';
    }

    @Override
    protected boolean recycle() {
        super.recycle();
        this.count = 0;
        this.specify = null;
        if (this.content != null && this.content.length > defaultSize) {
            this.content = new char[defaultSize];
        }
        return true;
    }

    public ByteBuffer[] toBuffers() {
        return new ByteBuffer[]{ByteBuffer.wrap(Utility.encodeUTF8(content, 0, count))};
    }

    public byte[] toBytes() {
        return Utility.encodeUTF8(content, 0, count);
    }

    public int count() {
        return this.count;
    }

    @Override
    public void writeString(String value) {
        if (value == null) {
            writeNull();
            return;
        }
        expand(value.length() * 2 + 2);
        content[count++] = '"';
        for (char ch : Utility.charArray(value)) {
            switch (ch) {
                case '\n':
                    content[count++] = '\\';
                    content[count++] = 'n';
                    break;
                case '\r':
                    content[count++] = '\\';
                    content[count++] = 'r';
                    break;
                case '\t':
                    content[count++] = '\\';
                    content[count++] = 't';
                    break;
                case '\\':
                    content[count++] = '\\';
                    content[count++] = ch;
                    break;
                case '"':
                    content[count++] = '\\';
                    content[count++] = ch;
                    break;
                default:
                    content[count++] = ch;
                    break;
            }
        }
        content[count++] = '"';
    }

    @Override
    public String toString() {
        return new String(content, 0, count);
    }

    //----------------------------------------------------------------------------------------------
    @Override
    public void writeBoolean(boolean value) {
        writeTo(value ? CHARS_TUREVALUE : CHARS_FALSEVALUE);
    }

    @Override
    public void writeInt(int value) {
        final char sign = value >= 0 ? 0 : '-';
        if (value < 0) value = -value;
        int size;
        for (int i = 0;; i++) {
            if (value <= sizeTable[i]) {
                size = i + 1;
                break;
            }
        }
        if (sign != 0) size++; //负数
        expand(size);

        int q, r;
        int charPos = count + size;

        // Generate two digits per iteration
        while (value >= 65536) {
            q = value / 100;
            // really: r = i - (q * 100);
            r = value - ((q << 6) + (q << 5) + (q << 2));
            value = q;
            content[--charPos] = DigitOnes[r];
            content[--charPos] = DigitTens[r];
        }

        // Fall thru to fast mode for smaller numbers
        // assert(i <= 65536, i);
        for (;;) {
            q = (value * 52429) >>> (16 + 3);
            r = value - ((q << 3) + (q << 1));  // r = i-(q*10) ...
            content[--charPos] = digits[r];
            value = q;
            if (value == 0) break;
        }
        if (sign != 0) content[--charPos] = sign;
        count += size;
    }

    @Override
    public void writeLong(long value) {
        final char sign = value >= 0 ? 0 : '-';
        if (value < 0) value = -value;
        int size = 19;
        long p = 10;
        for (int i = 1; i < 19; i++) {
            if (value < p) {
                size = i;
                break;
            }
            p = 10 * p;
        }
        if (sign != 0) size++; //负数
        expand(size);

        long q;
        int r;
        int charPos = count + size;

        // Get 2 digits/iteration using longs until quotient fits into an int
        while (value > Integer.MAX_VALUE) {
            q = value / 100;
            // really: r = i - (q * 100);
            r = (int) (value - ((q << 6) + (q << 5) + (q << 2)));
            value = q;
            content[--charPos] = DigitOnes[r];
            content[--charPos] = DigitTens[r];
        }

        // Get 2 digits/iteration using ints
        int q2;
        int i2 = (int) value;
        while (i2 >= 65536) {
            q2 = i2 / 100;
            // really: r = i2 - (q * 100);
            r = i2 - ((q2 << 6) + (q2 << 5) + (q2 << 2));
            i2 = q2;
            content[--charPos] = DigitOnes[r];
            content[--charPos] = DigitTens[r];
        }

        // Fall thru to fast mode for smaller numbers
        // assert(i2 <= 65536, i2);
        for (;;) {
            q2 = (i2 * 52429) >>> (16 + 3);
            r = i2 - ((q2 << 3) + (q2 << 1));  // r = i2-(q2*10) ...
            content[--charPos] = digits[r];
            i2 = q2;
            if (i2 == 0) break;
        }
        if (sign != 0) content[--charPos] = sign;
        count += size;
    }

}
