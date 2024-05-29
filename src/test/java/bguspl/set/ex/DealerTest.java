package bguspl.set.ex;

import bguspl.set.Config;
import bguspl.set.Env;
import bguspl.set.UserInterface;
import bguspl.set.Util;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedList;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DealerTest {

    Dealer dealer;
    @Mock
    Util util;
    @Mock
    private UserInterface ui;
    @Mock
    private Table table;
    @Mock
    private Player player;
    @Mock
    private Logger logger;
    @Mock
    private List<Integer> deck;


    void assertInvariants() {
        assertTrue(deck.size() >= 0);
        //assertTrue(players.length >= 0);
    }

    @BeforeEach
    void setUp() {
        //purposely do not find the configuration files (use defaults here).
        Player[] players = new Player[2];
        Env env = new Env(logger, new Config(logger, (String) null), ui, util);
        dealer = new Dealer(env, table, players);
        players[0] = new Player(env, dealer, table, 0, false);
        players[1] = new Player(env, dealer, table, 0, false);
        deck = new LinkedList<Integer>();
        assertInvariants();
    }
    
    @AfterEach
    void tearDown() {
        assertInvariants();
    }

    @Test
    void addToDeck() {
        int expectedSize = dealer.deck.size() + 1;
        dealer.addToDeck(1);
        assertEquals(expectedSize, dealer.deck.size(), "The deck size increased by one");
    }

    @Test
    void updateTimer(){
        dealer.updateTimer(100);
        assertEquals(100, dealer.reshuffleTime, "The reshuffle time was updated correctly");
    }

}
