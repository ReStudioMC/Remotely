package redxax.oxy.remotely.util;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class NameUuid {
    private static final int[] SHIFTS = {
            7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
            5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
            4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
            6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21
    };
    private static final int[] CONSTANTS = {
            0xd76aa478, 0xe8c7b756, 0x242070db, 0xc1bdceee, 0xf57c0faf, 0x4787c62a, 0xa8304613, 0xfd469501,
            0x698098d8, 0x8b44f7af, 0xffff5bb1, 0x895cd7be, 0x6b901122, 0xfd987193, 0xa679438e, 0x49b40821,
            0xf61e2562, 0xc040b340, 0x265e5a51, 0xe9b6c7aa, 0xd62f105d, 0x02441453, 0xd8a1e681, 0xe7d3fbc8,
            0x21e1cde6, 0xc33707d6, 0xf4d50d87, 0x455a14ed, 0xa9e3e905, 0xfcefa3f8, 0x676f02d9, 0x8d2a4c8a,
            0xfffa3942, 0x8771f681, 0x6d9d6122, 0xfde5380c, 0xa4beea44, 0x4bdecfa9, 0xf6bb4b60, 0xbebfbc70,
            0x289b7ec6, 0xeaa127fa, 0xd4ef3085, 0x04881d05, 0xd9d4d039, 0xe6db99e5, 0x1fa27cf8, 0xc4ac5665,
            0xf4292244, 0x432aff97, 0xab9423a7, 0xfc93a039, 0x655b59c3, 0x8f0ccc92, 0xffeff47d, 0x85845dd1,
            0x6fa87e4f, 0xfe2ce6e0, 0xa3014314, 0x4e0811a1, 0xf7537e82, 0xbd3af235, 0x2ad7d2bb, 0xeb86d391
    };

    private NameUuid() {
    }

    public static UUID from(String value) {
        byte[] digest = md5(value.getBytes(StandardCharsets.UTF_8));
        digest[6] = (byte) (digest[6] & 0x0f | 0x30);
        digest[8] = (byte) (digest[8] & 0x3f | 0x80);
        StringBuilder formatted = new StringBuilder(36);
        for (int index = 0; index < digest.length; index++) {
            if (index == 4 || index == 6 || index == 8 || index == 10) {
                formatted.append('-');
            }
            int valueByte = digest[index] & 0xff;
            formatted.append(Character.forDigit(valueByte >>> 4, 16));
            formatted.append(Character.forDigit(valueByte & 0xf, 16));
        }
        return UUID.fromString(formatted.toString());
    }

    private static byte[] md5(byte[] input) {
        int paddedLength = (input.length + 72) / 64 * 64;
        byte[] message = new byte[paddedLength];
        System.arraycopy(input, 0, message, 0, input.length);
        message[input.length] = (byte) 0x80;
        long bitLength = (long) input.length * 8;
        for (int index = 0; index < 8; index++) message[paddedLength - 8 + index] = (byte) (bitLength >>> index * 8);
        int a = 0x67452301;
        int b = 0xefcdab89;
        int c = 0x98badcfe;
        int d = 0x10325476;
        int[] words = new int[16];
        for (int offset = 0; offset < message.length; offset += 64) {
            for (int index = 0; index < words.length; index++) {
                int position = offset + index * 4;
                words[index] = message[position] & 0xff | (message[position + 1] & 0xff) << 8
                        | (message[position + 2] & 0xff) << 16 | (message[position + 3] & 0xff) << 24;
            }
            int blockA = a;
            int blockB = b;
            int blockC = c;
            int blockD = d;
            for (int index = 0; index < 64; index++) {
                int function;
                int word;
                if (index < 16) {
                    function = blockB & blockC | ~blockB & blockD;
                    word = index;
                } else if (index < 32) {
                    function = blockD & blockB | ~blockD & blockC;
                    word = (5 * index + 1) & 15;
                } else if (index < 48) {
                    function = blockB ^ blockC ^ blockD;
                    word = (3 * index + 5) & 15;
                } else {
                    function = blockC ^ (blockB | ~blockD);
                    word = 7 * index & 15;
                }
                int next = blockD;
                blockD = blockC;
                blockC = blockB;
                blockB += Integer.rotateLeft(blockA + function + CONSTANTS[index] + words[word], SHIFTS[index]);
                blockA = next;
            }
            a += blockA;
            b += blockB;
            c += blockC;
            d += blockD;
        }
        byte[] digest = new byte[16];
        write(digest, 0, a);
        write(digest, 4, b);
        write(digest, 8, c);
        write(digest, 12, d);
        return digest;
    }

    private static void write(byte[] target, int offset, int value) {
        target[offset] = (byte) value;
        target[offset + 1] = (byte) (value >>> 8);
        target[offset + 2] = (byte) (value >>> 16);
        target[offset + 3] = (byte) (value >>> 24);
    }
}
