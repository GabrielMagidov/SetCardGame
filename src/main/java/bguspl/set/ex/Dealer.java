package bguspl.set.ex;

import bguspl.set.Env;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;
    private final Player[] players;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    protected final List<Integer> deck;

    /**
     * True iff game should be terminated due to an external event.
     */
    private volatile boolean terminate;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    protected long reshuffleTime = Long.MAX_VALUE;


    /**
     * A list that represents the slots
     */
    private LinkedList<Integer> slots = new LinkedList<Integer>();


    /**
     * A queue for the players to insert their cards to check if its a set
     */
    protected ConcurrentLinkedQueue<int[]> cardsToCheck = new ConcurrentLinkedQueue<int[]>();


    public Dealer(Env env, Table table, Player[] players) {
        terminate = false;
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        for(int i = 0; i < env.config.tableSize; i++){
            slots.add(i);
        }
    }

    protected boolean stop = true;

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        env.logger.info("Thread " + Thread.currentThread().getName() + " starting.");
        for(int i = 0; i < players.length; i++){
            Thread playerThread = new Thread(players[i]);
            playerThread.start();
        }
        while (!shouldFinish()) {
            placeCardsOnTable();
            timerLoop();
            if(!terminate){
                updateTimerDisplay(true);
                removeAllCardsFromTable();
            }
        }
        if(!terminate){
            terminate();
        }
        announceWinners();
        for(int i = players.length - 1; i >= 0; i--){
            try {
                players[i].playerThread.join();
            } catch (InterruptedException e) {}
        }
        env.logger.info("Thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() {
        synchronized(this){
            stop = false;
        }
        reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis + 500;
        updateTimerDisplay(false);
        while (!terminate && System.currentTimeMillis() < reshuffleTime) {
            sleepUntilWokenOrTimeout();
            updateTimerDisplay(false);
            removeCardsFromTable();
            placeCardsOnTable();
            
        }
        synchronized(this){
            stop = true;
        }
    }

    /**
     * Called when the game should be terminated due to an external event.
     */
    public void terminate() {
        for(int i = players.length - 1; i >= 0; i--){
            players[i].terminate();
            players[i].playerThread.interrupt();
        }
        terminate = true;
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        return terminate || env.util.findSets(deck, 1).size() == 0;
    }

    /**
     * Checks cards should be removed from the table and removes them.
     */
    private void removeCardsFromTable() {
        if(cardsToCheck.size() > 0 && System.currentTimeMillis() < reshuffleTime){
            int[] temp = cardsToCheck.remove();
            int playerId = temp[0];
            int[] cards = {temp[1], temp[2], temp[3]};
            Integer [] slots = {table.cardToSlot[cards[0]],table.cardToSlot[cards[1]],table.cardToSlot[cards[2]]};
            synchronized(table){
                if(env.util.testSet(cards) && !stop){
                    stop = true;
                    updateTimerDisplay(true);
                    for (Player player: players){
                        if(player.id != playerId){
                            player.resetTokens(cards);
                        }
                    }
                    for(int i = 0; i < cards.length; i++){
                        table.removeToken(playerId, table.cardToSlot[cards[i]]);
                        table.removeCard(slots[i]);
                    }
                    for(int[] cardst : cardsToCheck){
                        boolean removed = false;
                        for(int i = 1; i < cardst.length && !removed; i++){
                            if(cardst[i] == cards[0]){
                                removed = true;
                                cardsToCheck.remove(cardst);
                            }
                            else if(cardst[i] == cards[1]){
                                removed = true;
                                cardsToCheck.remove(cardst);
                            }
                            else if(cardst[i] == cards[2]){
                                removed = true;
                                cardsToCheck.remove(cardst);
                            }
                            if(removed){
                                synchronized(players[cardst[0]].playerThread){
                                    players[cardst[0]].playerThread.notify();
                                }        
                            }
                        }
                    }
                    players[playerId].setFlag(1);
                    synchronized(players[playerId].playerThread){
                        players[playerId].playerThread.notify();
                    }
                }
                else{
                    stop = false;
                    players[playerId].setFlag(2);
                    synchronized(players[playerId].playerThread){
                        players[playerId].playerThread.notify();
                    }
                }

        }
        }
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
        synchronized(table){
            Collections.shuffle(slots);
            List<Integer> checkIfSetLeft = new LinkedList<Integer>(deck);
            for(int i = 0; i < env.config.tableSize; i++){
                if(table.slotToCard[i] != null){
                    checkIfSetLeft.add(table.slotToCard[i]);
                }
            }
            if(env.util.findSets(checkIfSetLeft, 1).size() != 0){
                    Collections.shuffle(deck);
                    for(int slot : slots){
                        if(table.slotToCard[slot] == null){
                            if(!deck.isEmpty()){
                                table.placeCard(deck.remove(0), slot);
                            }
                            else{
                                for(Player player: players){
                                    player.removeTokens(slot);
                                }
                            }
                        }
                    }
                    for(Player player: players){
                        if(player.getFlag() == -1){
                            player.playerThread.interrupt();
                        }
                    }
            }
            else{
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {}
                table.removeAllTokens();
                terminate();
            }
        }
        stop = false;
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private void sleepUntilWokenOrTimeout() {
        if(!terminate && cardsToCheck.isEmpty()){
            if(reshuffleTime - System.currentTimeMillis() > env.config.turnTimeoutWarningMillis){
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {}
            }
            else{
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {}
            }
        }
        


    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        if(reset){
            reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis + 999;
        }
        if(reshuffleTime - System.currentTimeMillis() > env.config.turnTimeoutWarningMillis){
            env.ui.setCountdown(reshuffleTime - System.currentTimeMillis(), false);
        }
        else{
            env.ui.setCountdown(Math.max(reshuffleTime - System.currentTimeMillis(), 0), true);
        }

    }

    protected void updateTimer(int time){
        reshuffleTime = time;
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        stop = true;
        cardsToCheck.clear();
        env.ui.removeTokens();
        Collections.shuffle(slots);
        for(int slot : slots){
            if (table.slotToCard[slot] != null){
                deck.add(table.slotToCard[slot]);
                for(Player p : players){
                    p.resetTokens();
                    p.setFlag(-1);
                }
                table.removeCard(slot);
            }
        }    
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        int counter = 0;
        int maxScore = 0;
        for(int i = 0; i < players.length; i++){
            if(players[i].score() > maxScore){
                counter = 1;
                maxScore = players[i].score();
            }
            else if(players[i].score() == maxScore){
                counter++;
            }
        }
        int[] scores = new int[counter];
        int j = 0;
        for(int i = 0; i < players.length; i++){
            if(players[i].score() == maxScore){
                scores[j] = players[i].id;
                j++;
            }
        }
        env.ui.announceWinner(scores);
    }

    public boolean getTerminate(){
        return terminate;
    }

    protected void addToDeck(Integer toAdd){
        deck.add(toAdd);
    }

}
