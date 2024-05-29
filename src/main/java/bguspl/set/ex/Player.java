package bguspl.set.ex;

import bguspl.set.Env;

import java.util.concurrent.ConcurrentLinkedQueue;

//import org.omg.PortableServer.THREAD_POLICY_ID;

/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;

    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * The thread representing the current player.
     */
    protected Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate key presses).
     */
    private Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated due to an external event.
     */
    private volatile boolean terminate;

    /**
     * The current score of the player.
     */
    private int score;

    /**
     * Number of tokens placed by the player
     */
    private int numTokens = 0;

    /**
     * A boolean variable that checks if a player removed at least one token after a set
     */
    protected boolean changedAfterWrongSet = true;

    /**
     * Array of the slots in which there are tokens on
     */
    private int[] tokens = {-1, -1, -1};

    protected Dealer dealer;

    /**
     * A flag to know if the player should get a point or a penalty
     */
    enum STATUS{
        PLAYING,
        POINT,
        PENALTY
    }

    private STATUS status;

    /**
     * A queue that always has at most 3 values which are the slots that were pressed by the user
     */
    private ConcurrentLinkedQueue<Integer> slotsPressed = new ConcurrentLinkedQueue<Integer>();

    private boolean canPress = true;

    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.dealer = dealer;
        this.table = table;
        this.id = id;
        this.human = human;
        status = STATUS.PLAYING;
    }

    /**
     * The main player thread of each player starts here (main loop for the player thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        env.logger.info("Thread " + Thread.currentThread().getName() + " starting.");
        if (!human) createArtificialIntelligence();

        while (!terminate) {
            if (status == STATUS.POINT){
                point();
            }
            if (status == STATUS.PENALTY){
                penalty();
            }
            if(numTokens == 3 && changedAfterWrongSet){
                changedAfterWrongSet = false;
                int[] cards = new int[4];
                cards[0] = id;
                for (int i = 1; i < cards.length; i++){
                    if((tokens[i-1] != -1) && (table.slotToCard[tokens[i - 1]] != null)){
                        cards[i] = table.slotToCard[tokens[i - 1]];
                    }
                    else{
                        tokens[i-1] = -1;
                        numTokens--;
                    }
                }
                if(numTokens == 3){
                    synchronized(dealer.cardsToCheck){
                        dealer.cardsToCheck.add(cards);
                    }
                    synchronized(playerThread){
                        try {
                            playerThread.wait();
                        } catch (InterruptedException e) {}
                    }
                }
            }
            if(!dealer.stop && status == STATUS.PLAYING){
                addToArray();
            }
        }
        if (!human) try { aiThread.join(); } catch (InterruptedException ignored) {}
        env.logger.info("Thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very very smart AI (!)
        aiThread = new Thread(() -> {
            env.logger.info("Thread " + Thread.currentThread().getName() + " starting.");            
            while (!terminate) {
                try {
                    synchronized (this) { wait(100); }
                } catch (InterruptedException ignored) {}
                while(slotsPressed.size() <= 3){
                    int slot = (int) Math.floor(Math.random()*env.config.tableSize);
                    if(table.slotToCard[slot] != null){
                        keyPressed(slot);
                    }
                }
            }
            env.logger.info("Thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        aiThread.start();
    }

    /**
     * Called when the game should be terminated due to an external event.
     */
    public void terminate() {
        terminate = true;
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
        if(status == STATUS.PLAYING && canPress && slotsPressed.size() <= 3 && table.slotToCard[slot] != null && !terminate && !dealer.stop){
            slotsPressed.add(slot);
        }
    }

    /**
     * This method adds to the array tokens, this array will hold the slots the player pressed on
     */
    private void addToArray(){
        if(!slotsPressed.isEmpty() && !terminate && !dealer.stop){
            int slot = slotsPressed.remove();
            boolean removed = false;
            for (int i = 0; i < 3; i++){
                if(tokens[i] == slot){
                    tokens[i] = -1;
                    numTokens--;
                    changedAfterWrongSet = true;
                    removed = table.removeToken(id, slot);
                }
                canPress = true;
            }
            if(!removed && (tokens[0] == -1 || tokens[1] == -1 || tokens[2] == -1) && table.slotToCard[slot] != null && !dealer.stop){
                int i = 0;
                while(tokens[i] != -1){
                    i++;
                }
                if(tokens[i] != slot){
                    tokens[i] = slot;
                    numTokens++;
                    if(numTokens == 3){
                        canPress = false;
                    }
                    table.placeToken(id, slot);
                }
            }
            
        }
    }

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
        env.ui.setScore(id, ++score);
        long i = env.config.pointFreezeMillis;
        if(i != 0){
            i += 500;
            env.ui.setFreeze(id, i);
        }
        while (i > 500){
            try{
                Thread.sleep(1000);
            }
            catch(InterruptedException e) {}
            i = i - 1000;
            env.ui.setFreeze(id, i);
        }
        env.ui.setFreeze(id, 0);
        
        tokens[0] = -1;
        tokens[1] = -1;
        tokens[2] = -1;
        numTokens = 0;
        changedAfterWrongSet = true;
        canPress = true;
        status = STATUS.PLAYING;
        int ignored = table.countCards(); // this part is just for demonstration in the unit tests
    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        long i = env.config.penaltyFreezeMillis;
        if(i != 0){
            i += 500;
            env.ui.setFreeze(id, i);
        }

        while (i > 500){
            try{
                Thread.sleep(1000);
            }
            catch(InterruptedException e) {}
            i = i - 1000;
            env.ui.setFreeze(id, i);
        }

        env.ui.setFreeze(id, 0);
        canPress = true;
        status = STATUS.PLAYING;

    }

    public int score() {
        return score;
    }

    public void setFlag(int newStatus){
        if(newStatus == -1){
            status = STATUS.PLAYING;
        }
        else if(newStatus == 1){
            status = STATUS.POINT;
        }
        else if(newStatus == 2){
            status = STATUS.PENALTY;
        }
    }

    /**
     *This method resets all the tokens of this player
     */
    public void resetTokens(){
        status = STATUS.PLAYING;
        tokens[0] = -1;
        tokens[1] = -1;
        tokens[2] = -1;
        canPress = true;
        changedAfterWrongSet = true;
        slotsPressed.clear();
        numTokens = 0;
    }


    /**
     * This method gets an array of slots, and checks if this player has tokens on some of the slots,
     * if so, it removes the tokens from these slots.
     * @param toRemove
     */
    public void resetTokens(int[] toRemove){
        for (int j = 0; j < toRemove.length; j++) {
            if(table.cardToSlot[toRemove[j]] != null){
                int slot = table.cardToSlot[toRemove[j]];
                for(int i = 0; i < tokens.length; i++){
                    if(slot == tokens[i]){
                        tokens[i] = -1;
                        numTokens--;
                        canPress = true;
                        changedAfterWrongSet = true;
                        table.removeToken(id, slot);
                    }
                }
            }
        }
    }

    /**
     * This method gets a slot and removes the token from it, if exists.
     * @param slot
     */
    public void removeTokens(int slot){
        if(tokens[0] == slot){
            tokens[0] = -1;
            numTokens --;
            canPress = true;
            changedAfterWrongSet = true;
            table.removeToken(id, slot);
        }
        else if(tokens[1] == slot){
            tokens[1] = -1;
            numTokens --;
            canPress = true;
            changedAfterWrongSet = true;
            table.removeToken(id, slot);
        }
        else if(tokens[2] == slot){
            tokens[2] = -1;
            numTokens --;
            canPress = true;
            changedAfterWrongSet = true;
            table.removeToken(id, slot);
        }
    }

    public int getFlag(){
        if(status == STATUS.PLAYING){
            return -1;
        }
        else if(status == STATUS.POINT){
            return 1;
        }
        else if(status == STATUS.PENALTY){
            return 2;
        }
        return 0;
    }
}
