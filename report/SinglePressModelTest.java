/*
 * Headless model of AutoRightClient.handleSinglePress()'s rising-edge contract.
 *
 * In game, on a SINGLE_PRESS item:
 *   - rising edge (mouseDown && !prevDown): AutoRight does NOT touch the use key,
 *     so vanilla emits exactly ONE use for that physical press.
 *   - held (mouseDown && !rising): AutoRight forces the use key released, so
 *     vanilla's itemUseCooldown loop cannot re-fire => no extra uses.
 *   - released: nothing; a new press is required.
 *
 * Therefore effective uses == number of rising edges == number of distinct presses.
 * This model encodes exactly that rule and asserts the acceptance criteria.
 */
public class SinglePressModelTest {

    /** Returns the number of item uses produced for a sequence of per-tick button states. */
    static int simulate(boolean[] mouseDownPerTick) {
        boolean prevDown = false;
        int uses = 0;
        for (boolean down : mouseDownPerTick) {
            boolean rising = down && !prevDown;
            if (rising) {
                uses++;            // vanilla fires one use on the fresh press
            }
            // held (down && !rising) => suppressed; released => nothing
            prevDown = down;
        }
        return uses;
    }

    static void check(String name, int actual, int expected) {
        String status = actual == expected ? "PASS" : "FAIL";
        System.out.printf("[%s] %-46s expected=%d actual=%d%n", status, name, expected, actual);
        if (actual != expected) failures++;
    }

    static int failures = 0;

    public static void main(String[] args) {
        // 1) 20 separate presses (press one tick, release one tick) => 20 uses
        boolean[] twenty = new boolean[80];
        for (int i = 0; i < 20; i++) {
            twenty[i * 4] = true;          // press
            // twenty[i*4+1..+3] = false   // release/gap
        }
        check("20 separate presses = 20 uses", simulate(twenty), 20);

        // 2) hold for 200 ticks => exactly 1 use
        boolean[] held = new boolean[200];
        java.util.Arrays.fill(held, true);
        check("hold 200 ticks = 1 use", simulate(held), 1);

        // 3) press, release, press => 2 uses
        check("press/release/press = 2 uses",
                simulate(new boolean[]{true, true, true, false, false, true, true}), 2);

        // 4) long realistic press (10 ticks) then release, x5 => 5 uses
        boolean[] fiveLong = new boolean[100];
        for (int i = 0; i < 5; i++) {
            for (int t = 0; t < 10; t++) fiveLong[i * 20 + t] = true;   // 10-tick hold
        }
        check("5 long holds = 5 uses", simulate(fiveLong), 5);

        // 5) never pressed => 0 uses
        check("no press = 0 uses", simulate(new boolean[50]), 0);

        System.out.println(failures == 0 ? "\nALL PASS" : "\n" + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }
}
