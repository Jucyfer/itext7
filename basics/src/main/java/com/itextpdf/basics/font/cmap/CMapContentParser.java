package com.itextpdf.basics.font.cmap;

import com.itextpdf.basics.PdfException;
import com.itextpdf.basics.font.PdfEncodings;
import com.itextpdf.basics.io.ByteBuffer;
import com.itextpdf.basics.io.PdfTokeniser;
import com.itextpdf.basics.io.PdfTokeniser.TokenType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class CMapContentParser {

    /**
     * Commands have this type.
     */
    public static final int COMMAND_TYPE = 200;

    /**
     * Holds value of property tokeniser.
     */
    private PdfTokeniser tokeniser;

    /**
     * Creates a new instance of PdfContentParser
     * @param tokeniser the tokeniser with the content
     */
    public CMapContentParser(PdfTokeniser tokeniser) {
        this.tokeniser = tokeniser;
    }

    /**
     * Parses a single command from the content. Each command is output as an array of arguments
     * having the command itself as the last element. The returned array will be empty if the
     * end of content was reached.
     * @param ls an {@code ArrayList} to use. It will be cleared before using.
     * @throws IOException on error
     */
    public void parse(ArrayList<CMapObject> ls) throws IOException {
        ls.clear();
        CMapObject ob;
        while ((ob = readObject()) != null) {
            ls.add(ob);
            // TokenType.Other or CMapObject.Literal means a command
            if (ob.isLiteral())
                break;
        }
    }

    /**
     * Reads a dictionary. The tokeniser must be positioned past the {@code "<<"} token.
     * @return the dictionary
     * @throws IOException on error
     */
    public CMapObject readDictionary() throws IOException {
        Map<String, CMapObject> dic = new HashMap<>();
        while (true) {
            if (!nextValidToken())
                throw new PdfException("unexpected.end.of.file");
            if (tokeniser.getTokenType() == TokenType.EndDic)
                break;
            if (tokeniser.getTokenType() == TokenType.Other && "def".equals(tokeniser.getStringValue()))
                continue;
            if (tokeniser.getTokenType() != TokenType.Name)
                throw new PdfException("dictionary.key.1.is.not.a.name").setMessageParams(tokeniser.getStringValue());
            String name = tokeniser.getStringValue();
            CMapObject obj = readObject();
            if (obj.isToken()) {
                if (obj.toString().equals(">>")) {
                    tokeniser.throwError(PdfException.UnexpectedGtGt);
                }
                if (obj.toString().equals("]")) {
                    tokeniser.throwError(PdfException.UnexpectedCloseBracket);
                }
            }
            dic.put(name, obj);
        }
        return new CMapObject(CMapObject.Dictionary, dic);
    }

    /**
     * Reads an array. The tokeniser must be positioned past the "[" token.
     * @return an array
     * @throws IOException on error
     */
    public CMapObject readArray() throws IOException {
        ArrayList<CMapObject> array = new ArrayList<CMapObject>();
        while (true) {
            CMapObject obj = readObject();
            if (obj.isToken()) {
                if (obj.toString().equals("]")) {
                    break;
                }
                if (obj.toString().equals(">>")) {
                    tokeniser.throwError(PdfException.UnexpectedGtGt);
                }
            }
            array.add(obj);
        }
        return new CMapObject(CMapObject.Array, array);
    }

    /**
     * Reads a pdf object.
     * @return the pdf object
     * @throws IOException on error
     */
    public CMapObject readObject() throws IOException {
        if (!nextValidToken())
            return null;
        TokenType type = tokeniser.getTokenType();
        switch (type) {
            case StartDic:
                return readDictionary();
            case StartArray:
                return new CMapObject(CMapObject.Array, readArray());
            case String:
                CMapObject obj;
                if (tokeniser.isHexString()) {
                    obj = new CMapObject(CMapObject.HexString, decodeHexString(tokeniser.getByteContent()));
                } else {
                    obj = new CMapObject(CMapObject.String, decodeString(tokeniser.getByteContent()));
                }
                return obj;
            case Name:
                return new CMapObject(CMapObject.Name, decodeName(tokeniser.getByteContent()));
            case Number:
                CMapObject numObject = new CMapObject(CMapObject.Number, null);
                try {
                    numObject.setValue((int)java.lang.Double.parseDouble(tokeniser.getStringValue()));
                } catch (NumberFormatException e) {
                    numObject.setValue(Integer.MIN_VALUE);
                }
                return numObject;
            case Other:
                return new CMapObject(CMapObject.Literal, tokeniser.getStringValue());
            case EndArray:
                return new CMapObject(CMapObject.Token, "]");
            case EndDic:
                return new CMapObject(CMapObject.Token, ">>");
            default:
                return new CMapObject(0, "");
        }
    }

    /**
     * Reads the next token skipping over the comments.
     * @return {@code true} if a token was read, {@code false} if the end of content was reached.
     * @throws IOException on error.
     */
    public boolean nextValidToken() throws IOException {
        while (tokeniser.nextToken()) {
            if (tokeniser.getTokenType() == TokenType.Comment)
                continue;
            return true;
        }
        return false;
    }

    protected static String decodeName(byte[] content) {
        StringBuilder buf = new StringBuilder();
        try {
            for (int k = 0; k < content.length; ++k) {
                char c = (char) content[k];
                if (c == '#') {
                    byte c1 = content[k + 1];
                    byte c2 = content[k + 2];
                    c = (char) ((ByteBuffer.getHex(c1) << 4) + ByteBuffer.getHex(c2));
                    k += 2;
                }
                buf.append(c);
            }
        } catch (IndexOutOfBoundsException e) {
            // empty on purpose
        }
        return buf.toString();
    }

    private static String toHex4(int n) {
        String s = "0000" + Integer.toHexString(n);
        return s.substring(s.length() - 4);
    }

    /**
     * Gets an hex string in the format "&lt;HHHH&gt;".
     *
     * @param n the number
     * @return the hex string
     */
    public static String toHex(int n) {
        if (n < 0x10000)
            return "<" + toHex4(n) + ">";
        n -= 0x10000;
        int high = n / 0x400 + 0xd800;
        int low = n % 0x400 + 0xdc00;
        return "[<" + toHex4(high) + toHex4(low) + ">]";
    }

    protected static String decodeHexString(byte[] content) {
        StringBuilder buffer = new StringBuilder();
        for (int i = 0; i < content.length; ) {
            int v1 = ByteBuffer.getHex(content[i++]);
            if (i == content.length) {
                buffer.append((char)(v1 << 4));
                break;
            }
            int v2 = content[i++];
            v2 = ByteBuffer.getHex(v2);
            buffer.append((char)((v1 << 4) + v2));
        }
        return buffer.toString();
    }

    protected static String decodeString(byte[] content) {
        StringBuilder buffer = new StringBuilder(content.length);
        for (int i = 0; i < content.length; ) {
            //with operator '& 0xff' we convert unsigned byte to integer (-128 byte to 128 int)
            int ch = content[i++] & 0xff;
            if (ch == '\\') {
                boolean lineBreak = false;
                ch = content[i++] & 0xff;
                switch (ch) {
                    case 'n':
                        ch = '\n';
                        break;
                    case 'r':
                        ch = '\r';
                        break;
                    case 't':
                        ch = '\t';
                        break;
                    case 'b':
                        ch = '\b';
                        break;
                    case 'f':
                        ch = '\f';
                        break;
                    case '(':
                    case ')':
                    case '\\':
                        break;
                    case '\r':
                        lineBreak = true;
                        if (i < content.length && content[i++] != '\n') {
                            i--;
                        }
                        break;
                    case '\n':
                        lineBreak = true;
                        break;
                    default: {
                        if (ch < '0' || ch > '7') {
                            break;
                        }
                        int octal = ch - '0';
                        ch = content[i++] & 0xff;
                        if (ch < '0' || ch > '7') {
                            i--;
                            ch = octal;
                            break;
                        }
                        octal = (octal << 3) + ch - '0';
                        ch = content[i++] & 0xff;
                        if (ch < '0' || ch > '7') {
                            i--;
                            ch = octal;
                            break;
                        }
                        octal = (octal << 3) + ch - '0';
                        ch = octal & 0xff;
                        break;
                    }
                }
                if (lineBreak) {
                    continue;
                }
            } else if (ch == '\r') {
                // in this case current char is '\n' and we have to skip next '\n' if it presents.
                ch = '\n';
                if (i < content.length && content[i++] != '\n') {
                    i--;
                }
            }
            buffer.append((char)ch);
        }
        return buffer.toString();
    }

    public static String decodeCMapObject(CMapObject cMapObject) {
        if (cMapObject.isHexString()) {
            return PdfEncodings.convertToString(((String) cMapObject.getValue()).getBytes(), PdfEncodings.UnicodeBigUnmarked);
        } else {
            return (String) cMapObject.getValue();
        }
    }
}
