package forge.cardocr.reader;

/**
 * All card names as a prefix tree, stored flat in depth-first order, so walking it visits memory in
 * sequence and needs no objects per node.
 *
 * <p>A node's subtree is the nodes from it up to {@link #subtreeEnd(int)}. Characters are classes 0
 * to 35: see {@link CharClasses}.
 */
public final class NameTrie {

    private final byte[] symbol;
    private final byte[] depth;
    private final byte[] deepest;
    private final int[] card;
    private final int[] subtreeEnd;
    private final int maxDepth;

    /** Relies on the names being sorted, as a name then shares its prefix with the one before it. */
    public NameTrie(CardNames names) {
        int nodes = 0;
        String previous = "";
        for (int i = 0; i < names.size(); i++) {
            String name = names.normalised(i);
            nodes += name.length() - commonPrefix(previous, name);
            previous = name;
        }
        symbol = new byte[nodes];
        depth = new byte[nodes];
        deepest = new byte[nodes];
        card = new int[nodes];
        subtreeEnd = new int[nodes];
        java.util.Arrays.fill(card, -1);

        int[] pathNode = new int[256];
        int next = 0;
        int longest = 0;
        previous = "";
        for (int i = 0; i < names.size(); i++) {
            String name = names.normalised(i);
            int shared = commonPrefix(previous, name);
            for (int d = previous.length() - 1; d >= shared; d--) {
                close(pathNode[d], next);
            }
            for (int d = shared; d < name.length(); d++) {
                pathNode[d] = next;
                symbol[next] = (byte) CharClasses.symbolOf(name.charAt(d));
                depth[next] = (byte) (d + 1);
                deepest[next] = (byte) (d + 1);
                next++;
            }
            card[pathNode[name.length() - 1]] = i;
            longest = Math.max(longest, name.length());
            previous = name;
        }
        for (int d = previous.length() - 1; d >= 0; d--) {
            close(pathNode[d], next);
        }
        maxDepth = longest;
    }

    private void close(int node, int end) {
        subtreeEnd[node] = end;
        for (int child = node + 1; child < end; child = subtreeEnd[child]) {
            deepest[node] = (byte) Math.max(deepest[node] & 255, deepest[child] & 255);
        }
    }

    private static int commonPrefix(String a, String b) {
        int n = Math.min(a.length(), b.length());
        int i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        return i;
    }

    public int nodeCount() {
        return symbol.length;
    }

    public int maxDepth() {
        return maxDepth;
    }

    /** Which name ends at this node, or -1. */
    public int card(int node) {
        return card[node];
    }

    public int symbol(int node) {
        return symbol[node];
    }

    public int depth(int node) {
        return depth[node] & 255;
    }

    /** The longest name in this node's subtree, in characters. */
    public int deepest(int node) {
        return deepest[node] & 255;
    }

    public int subtreeEnd(int node) {
        return subtreeEnd[node];
    }

    /** Roughly what the arrays occupy. */
    public long bytes() {
        return symbol.length * 3L + card.length * 8L;
    }
}
