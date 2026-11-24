package dbviewer;

import java.util.Comparator;

/**
 * A natural-order string comparator, in the spirit of {@code strnatcmp}/{@code natcmp}.
 * Digit runs are compared as numbers rather than character by character, so
 * {@code item2} sorts before {@code item10}. Non-numeric text is compared
 * case-insensitively.
 */
final class NatCmp implements Comparator<String> {

    static final NatCmp INSTANCE = new NatCmp();

    private NatCmp() {
    }

    @Override
    public int compare(String left, String right) {
        if (left == right) {
            return 0;
        }
        if (left == null) {
            return -1;
        }
        if (right == null) {
            return 1;
        }

        int i = 0;
        int j = 0;
        while (i < left.length() && j < right.length()) {
            boolean leftDigit = isDigit(left.charAt(i));
            boolean rightDigit = isDigit(right.charAt(j));
            if (leftDigit && rightDigit) {
                int leftStart = i;
                int rightStart = j;
                while (i < left.length() && isDigit(left.charAt(i))) {
                    i++;
                }
                while (j < right.length() && isDigit(right.charAt(j))) {
                    j++;
                }
                int result = compareNumbers(
                        left, leftStart, i, right, rightStart, j);
                if (result != 0) {
                    return result;
                }
                continue;
            }

            char a = left.charAt(i);
            char b = right.charAt(j);
            int result = Character.compare(
                    Character.toLowerCase(a), Character.toLowerCase(b));
            if (result != 0) {
                return result;
            }
            // Case is ignored while scanning; break any final tie after all text is consumed.
            i++;
            j++;
        }
        int remaining = Integer.compare(left.length() - i, right.length() - j);
        return remaining != 0 ? remaining : left.compareTo(right);
    }

    /** Compares decimal runs without converting them, so very long numbers are safe. */
    private static int compareNumbers(
            String left, int leftStart, int leftEnd,
            String right, int rightStart, int rightEnd) {
        int a = skipZeros(left, leftStart, leftEnd);
        int b = skipZeros(right, rightStart, rightEnd);
        int aLength = leftEnd - a;
        int bLength = rightEnd - b;
        if (aLength != bLength) {
            return Integer.compare(aLength, bLength);
        }
        while (a < leftEnd) {
            int result = Character.compare(left.charAt(a++), right.charAt(b++));
            if (result != 0) {
                return result;
            }
        }
        // Equal numeric value: keep a stable, deterministic order for leading zeros.
        return Integer.compare(leftEnd - leftStart, rightEnd - rightStart);
    }

    private static int skipZeros(String value, int start, int end) {
        while (start < end && value.charAt(start) == '0') {
            start++;
        }
        return start;
    }

    private static boolean isDigit(char value) {
        return value >= '0' && value <= '9';
    }
}
