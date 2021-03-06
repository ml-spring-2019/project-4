/**
 * Copyright John Asmuth and Rutgers University 2009, all rights reserved.
 */

package edu.rutgers.rl3.comp;

import org.rlcommunity.rlglue.codec.AgentInterface;
import org.rlcommunity.rlglue.codec.types.Action;
import org.rlcommunity.rlglue.codec.types.Observation;
import org.rlcommunity.rlglue.codec.util.AgentLoader;

import org.json.*;

import java.util.Date;
import java.util.Vector;
import java.util.Random;
import java.io.*;
import java.util.*;
import java.util.Hashtable;

/**
 * A simple agent that:
 * - never goes to the left
 * - mostly goes to the right
 * - tends to jump when under coins
 * - jumps if it cannot walk to the right due to a block
 * - tends to jump when there is a monster nearby
 * - tends to jump when there is a pit nearby
 * - tends to run when there is nothing nearby
 *
 * Also, it will remember the last trial, and repeat it exactly except
 * for the last 7 steps (assuming there are at least 7 steps).
 *
 * @author jasmuth
 *
 */
public class ExMarioAgent implements AgentInterface {

	private int NUMBER_OF_STATES = 13;
	private int NUMBER_OF_ACTIONS = 12;
	private final String export_filename = "ref_export.json";
/*
	States, Actions, Rewards
*/
	private ArrayList<Integer> state_vector;
	private ArrayList<Integer> action_vector;
	private ArrayList<Double> reward_vector;
	private int[] num_of_times_states_visited;
	private double[][] policy_table;
	private double[][] reward_table;
	private int[][] policy_itr_table;
	private double total_reward;
	private double episode_reward;
	private int episode_num;
	private int num_losses;
	private int num_wins;
	private Boolean isDead;

	Map<Integer, Integer> findActionCol;

	private PrintWriter debug_out;

	private Boolean[] cur_state;

	private int getIndexOfPolicyTable(Boolean[] states){
        String gen_string = "";
        for (boolean s : states){
            gen_string += s ? "1" : "0";
        }
//        System.out.println("[DEBUG]: gen_string: " + gen_string);
        return Integer.parseInt(gen_string, 2);
	}

	private void initializeStateRewards(){
        int rows = (int) Math.pow(2, NUMBER_OF_STATES);
        int cols = NUMBER_OF_ACTIONS;

        policy_table = new double[rows][NUMBER_OF_ACTIONS];
        reward_table = new double[rows][NUMBER_OF_ACTIONS];
        policy_itr_table = new int[rows][NUMBER_OF_ACTIONS];

        for (int i = 0; i < rows; i++){
            for (int j = 0; j < cols; j++){
                policy_table[i][j] = 0;
                reward_table[i][j] = 0.0;
                policy_itr_table[i][j] = 0;
            }
        }
	}

	private void flush_policy_itr_table(){
		int rows = (int) Math.pow(2, NUMBER_OF_STATES);
    int cols = NUMBER_OF_ACTIONS;
		for (int i = 0; i < rows; i++)
				for (int j = 0; j < cols; j++)
						policy_itr_table[i][j] = 0;
	}
	/*
	 * Returns the char representing the tile at the given location.
	 * If unknown, returns '\0'.
	 *
	 * Valid tiles:
	 * M - the tile mario is currently on. there is no tile for a monster.
	 * $ - a coin
	 * b - a smashable brick
	 * ? - a question block
	 * | - a pipe. gets its own tile because often there are pirahna plants
	 *     in them
	 * ! - the finish line
	 * And an integer in [1,7] is a 3 bit binary flag
	 *  first bit is "cannot go through this tile from above"
	 *  second bit is "cannot go through this tile from below"
	 *  third bit is "cannot go through this tile from either side"
	 *
	 * @param x
	 * @param y
	 * @param obs
	 * @return
	 */

	public static char getTileAt(double xf, double yf, Observation obs) {
		int x = (int)xf;
		if (x<0)
			return '7';
		int y = 16-(int)yf;
		x -= obs.intArray[0];
		if (x<0 || x>21 || y<0 || y>15)
			return '\0';
		int index = y*22+x;
		return obs.charArray[index];
	}

	/**
	 * All you need to know about a monster.
	 *
	 * @author jasmuth
	 *
	 */
	static class Monster {
		double x;
		double y;
		/**
		 * The instantaneous change in x per step
		 */
		double sx;
		/**
		 * The instantaneous change in y per step
		 */
		double sy;
		/**
		 * The monster type
		 * 0 - Mario
		 * 1 - Red Koopa
		 * 2 - Green Koopa
		 * 3 - Goomba
		 * 4 - Spikey
		 * 5 - Pirahna Plant
		 * 6 - Mushroom
		 * 7 - Fire Flower
		 * 8 - Fireball
		 * 9 - Shell
		 * 10 - Big Mario
		 * 11 - Fiery Mario
		 */
		int type;
		/**
		 * A human recognizable title for the monster
		 */
		String typeName;
		/**
		 * Winged monsters bounce up and down
		 */
		boolean winged;
	}

	/**
	 * Gets all the monsters from the observation. Mario is included in this list.
	 *
	 * @param obs
	 * @return
	 */
	public static Monster[] getMonsters(Observation obs) {
		Vector<Monster> monster_vec = new Vector<Monster>();
		for (int i=0; 1+2*i<obs.intArray.length; i++) {
			Monster m = new Monster();
			m.type = obs.intArray[1+2*i];
			m.winged = obs.intArray[2+2*i]!=0;
			switch (m.type) {
			case 0:
				m.typeName = "Mario";
				break;
			case 1:
				m.typeName = "Red Koopa";
				break;
			case 2:
				m.typeName = "Green Koopa";
				break;
			case 3:
				m.typeName = "Goomba";
				break;
			case 4:
				m.typeName = "Spikey";
				break;
			case 5:
				m.typeName = "Piranha Plant";
				break;
			case 6:
				m.typeName = "Mushroom";
				break;
			case 7:
				m.typeName = "Fire Flower";
				break;
			case 8:
				m.typeName = "Fireball";
				break;
			case 9:
				m.typeName = "Shell";
				break;
			case 10:
				m.typeName = "Big Mario";
				break;
			case 11:
				m.typeName = "Fiery Mario";
				break;
			}
			m.x = obs.doubleArray[4*i];
			m.y = obs.doubleArray[4*i+1];
			m.sx = obs.doubleArray[4*i+2];
			m.sy = obs.doubleArray[4*i+3];
			monster_vec.add(m);
		}
		return monster_vec.toArray(new Monster[0]);
	}
	/**
	 * Gets just mario's information.
	 *
	 * @param obs
	 * @return
	 */
	public static Monster getMario(Observation obs) {
		Monster[] monsters = getMonsters(obs);
		for (Monster m : monsters) {
			if (m.type == 0 || m.type == 10 || m.type == 11)
				return m;
		}
		return null;
	}

	Random rand;
	/**
	 * When this is true, Mario is pausing for some number of steps
	 */
	boolean walk_hesitating;
	/**
	 * How many steps since the beginning of this trial
	 */
	int step_number;
	/**
	 * How many steps since the beginning of this run
	 */
	int total_steps;
	/**
	 * The time that the current trial began
	 */
	long trial_start;

	/**
	 * The sequence of actions taken during the last trial
	 */
	Vector<Action> last_actions;
	/**
	 * The sequence of actions taken so far during the current trial
	 */
	Vector<Action> this_actions;

	int actionNum;

	int convertForFindActionCol(int direction, int jump, int speed){
		 int firstDigit = (direction + 1) * 100;
		 int secondDigit = jump * 10;
		 int thirdDigit = speed;
		 return firstDigit + secondDigit + thirdDigit;
	}

	ExMarioAgent() {
		// initialize variables
		num_of_times_states_visited = new int[(int)Math.pow(2, NUMBER_OF_STATES)];
		findActionCol = new Hashtable<Integer, Integer>();
		rand = new Random(new java.util.Date().getTime());
		last_actions = new Vector<Action>();
		this_actions = new Vector<Action>();
		cur_state = new Boolean[NUMBER_OF_STATES];
		state_vector = new ArrayList<Integer>();
		action_vector = new ArrayList<Integer>();
		reward_vector = new ArrayList<Double>();

		total_steps = 0;
		actionNum = 0;
        total_reward = 0;
        episode_num = 0;

		int counter = 0;
		for (int dir = -1; dir < 2; dir++){
			 for (int jum = 0; jum < 2; jum++){
				 	for (int spe = 0; spe < 2; spe++){
						findActionCol.put(convertForFindActionCol(dir, jum, spe), counter);
						counter++;
					}
			 }
		}

		initializeStateRewards();

        try {
            debug_out = new PrintWriter("debug_log.json", "UTF-8");
        } catch (Exception e){
            System.out.println("IO Error debugging.");
            return;
        }
        constructorDebugOut();
        importTablesFromFile();

	}

	public void agent_init(String task) {

	}

	public void agent_cleanup() {
        cleanupDebugOut();
        debug_out.close();
        exportTablesToFile();
        System.out.println("# wins: " + num_wins);
        System.out.println("# losses: " + num_losses);
	}

	private String getActionAsString(int a){
	    switch (a){
	        case 0:
	            return "walk back    ";
	        case 1:
	            return "run back     ";
	        case 2:
	            return "jump back    ";
	        case 3:
	            return "big jump back";
	        case 4:
	            return "idle         ";
	        case 5:
	            return "jump in place";
	        case 6:
	            return "idle         ";
	        case 7:
	            return "jump in place";
	        case 8:
	            return "walk forward ";
	        case 9:
	            return "run forward  ";
	        case 10:
	            return "jump forward ";
	        case 11:
	            return "big jump forw";
	        default:
	            return "unknown";

	    }
	}

	private void printPolicy(int num){
        System.out.println("[Episode " + episode_num + "]: Policy table for state " + num + ": ");
        double largest = Double.NEGATIVE_INFINITY;
        int largest_ind = -1;
		for (int i = 0; i < NUMBER_OF_ACTIONS; i++){
		    System.out.println(getActionAsString(i) + ": " + policy_table[num][i] + " \t itr: " + policy_itr_table[num][i]);
		    if (policy_table[num][i] > largest){
		        largest = policy_table[num][i];
		        largest_ind = i;
		    }
		}
		System.out.println("Largest: " + largest + " | Preferred action: " + getActionAsString(largest_ind));

	}

	public Action agent_start(Observation o) {
	    System.out.println("====================================================");
	    printPolicy(0);
//	    printPolicy(256);
//	    printPolicy(320);
//	    printPolicy(288);
		isDead = true;


		ArrayList episode = new ArrayList();

		for (int itr = 0; itr < num_of_times_states_visited.length; itr++){
			num_of_times_states_visited[itr] = 0;
        }

		episode_reward = 0;
		step_number = 0;

		trial_start = new Date().getTime();

        agentStartDebugOutBeforeAction();
		Action a = getAction(o);
		agentStartDebugOutAfterAction();

		episode_num++;
		return a;
	}

	public void updatePolicyTable() {
		for (int itr = 0; itr < reward_vector.size(); itr++){
		    int ind1 = state_vector.get(itr);
            int ind2 = action_vector.get(itr);
			double my_rew = episode_reward;

            policy_itr_table[ind1][ind2]++;
            reward_table[ind1][ind2] += my_rew;

			policy_table[ind1][ind2] = reward_table[ind1][ind2] / (double)(policy_itr_table[ind1][ind2]);
			episode_reward -= reward_vector.get(itr);
//			if (ind2 == 0 && ind1 == 0){
//			    System.out.println("reward_table[" + ind1 + "][" + ind2 + "]: " + reward_table[ind1][ind2] + "\t| policy_itr_table[" + ind1 + "][" + ind2 + "]: " + policy_itr_table[ind1][ind2] + "\t| policy_table[" + ind1 + "][" + ind2 + "]: " + policy_table[ind1][ind2] + "\t| episode_reward: " + episode_reward);
//			}
		}
	}

	public Action agent_step(double r, Observation o) {
		reward_vector.add(r);
		step_number++;
		total_steps++;
		total_reward += r;
		episode_reward += r;

//		updatePolicyTable(r, state_vector.get(state_vector.size()-1), action_vector.get(action_vector.size()-1));
		agentStepDebugOutStart(r);
		debug_out.println("\t\t{");

    Action a = getAction(o);
    agentStepDebugOutEnd(a);

		return a;
	}

	private void printResult(){
	    System.out.println("[Episode " + (episode_num - 1) + "]: " + (isDead ? "lost..." : "WIN!"));
	}

	public void agent_end(double r) {
		total_reward += r;
		episode_reward += r;
		reward_vector.add(r);
		updatePolicyTable();

		if (this_actions.size() > 7) {
			last_actions = this_actions;
			last_actions.setSize(last_actions.size()-7);
		}
		else
			last_actions = new Vector<Action>();
		this_actions = new Vector<Action>();

		for (int itr = 0; itr < num_of_times_states_visited.length; itr++)
			num_of_times_states_visited[itr] = 0;

		agentEndDebugOut();
        debug_out.print("\t}");

        if (isDead){
            num_losses++;
        } else {
            num_wins++;
        }
        printResult();

		reward_vector.clear();
		action_vector.clear();
		state_vector.clear();
	}

	public String agent_message(String msg) {
		System.out.println("message asked:"+msg);
		return null;
	}

	private static final int MONSTER_NEAR = 0;
	private static final int MONSTER_FAR = 1;
	private static final int MONSTER_ABOVE = 2;
	private static final int MONSTER_BELOW = 3;
	private static final int PIT_FAR = 4;
	private static final int PIT_NEAR = 5;
	private static final int QUESTION_BLOCK = 6;
	private static final int BONUS_ITEM_FAR = 7;
	private static final int BONUS_ITEM_NEAR = 8;
	private static final int COINS = 9;
	private static final int BREAKABLE_BLOCK = 10;
	private static final int HIGHER_GROUND_NEAR = 11;
	private static final int HIGHER_GROUND_FAR = 12;
	private static final int SOFT_POLICY = 4;

	int[] convertBackToAction(int code){
		 int direction = ((code - (code % 100)) / 100) - 1;
		 int jump = ((code % 100) - (code % 10)) / 10;
		 int speed = (code % 10);
		 return new int[]{direction, jump, speed};
	}

	int[] getActionFromActionIndex(int actionIndex){
		switch (actionIndex) {
			case 0:
				return new int[]{-1,0,0};
			case 1:
				return new int[]{-1,0,1};
			case 2:
				return new int[]{-1,1,0};
			case 3:
				return new int[]{-1,1,1};
			case 4:
				return new int[]{0,0,0};
			case 5:
				return new int[]{0,0,1};
			case 6:
				return new int[]{0,1,0};
			case 7:
				return new int[]{0,1,1};
			case 8:
				return new int[]{1,0,0};
			case 9:
				return new int[]{1,0,1};
			case 10:
				return new int[]{1,1,0};
			case 11:
				return new int[]{1,1,1};
			default:
					System.out.println("INVALID TOP ZACH BOI");
					return new int[]{-4,-4,-4};
			}
	}

// States: tiles
// states: monster, pit, pipe, regular block, unbreakable block, bonus items
	Action getAction(Observation o) {
		actionNum++;
		double jump_hesitation;
		double walk_hesitation;
		int speedRand;
		Random rand_ = new Random();
		int explorationProb = rand_.nextInt(SOFT_POLICY);

		//	The current state
		for (int state = 0; state < cur_state.length; state++)
			cur_state[state] = false;

		Action act = new Action(3, 0);

        Monster mario = ExMarioAgent.getMario(o);
        Monster[] monsters = ExMarioAgent.getMonsters(o);

        // if mario finishes the level, checking multiple tiles in case mario moves fast
        for (int i = 0; i < 5; i++){
            if (ExMarioAgent.getTileAt(mario.x + i, mario.y, o) == '!'){
                isDead = false;
            }
        }

        // check for ground higher than mario
        for (int r = 0; r < 6; r++){
            char tile = ExMarioAgent.getTileAt(mario.x+r, mario.y + 1, o);
            if (tile != ' ' && tile != 'M' && tile != '\0' && tile != '$'){
                if (r < 3){
                    cur_state[HIGHER_GROUND_NEAR] = true;
                } else {
                    cur_state[HIGHER_GROUND_FAR] = true;
                }
                break;
            }
        }


        /*
         * Check the blocks in the area to mario's upper right
         */
        for (int up=0; up<5; up++) {
            for (int right = 0; right<7; right++) {
                char tile = ExMarioAgent.getTileAt(mario.x+right, mario.y+up, o);
                if (tile == '?') cur_state[QUESTION_BLOCK] = true;
                if (tile == 'b') cur_state[BREAKABLE_BLOCK] = true;
                if (tile == '$') cur_state[COINS] = true;
            }
        }

        /*
         * Search for a pit in front of mario.
        */
        boolean is_pit = false;
        int right;
        for (right = 0; !is_pit && right<6; right++) {
            boolean pit_col = true;
            for (int down=0; pit_col && mario.y-down>=0; down++) {
                char tile = ExMarioAgent.getTileAt(mario.x+right, mario.y-down, o);
                if (tile != ' ' && tile != 'M' && tile != '\0')
                    pit_col = false;
            }
            if (pit_col){
                is_pit = true;
            }
        }

        if (is_pit && right < 3){
            cur_state[PIT_NEAR] = true;
        } else if (is_pit){
            cur_state[PIT_FAR] = true;
        }

        /*
         * Search for a pipe in front of mario.
         */
//        for (int right = 0; right<3; right++) {
//            char tile = ExMarioAgent.getTileAt(mario.x+right, mario.y, o);
//            if (tile == '|')
//                cur_state[PIPE] = true;
//                break;
//        }

        /*
         * Look for nearby monsters by checking the positions against mario's
         */
        for (Monster m : monsters) {
            if (m.type == 0 || m.type == 10 || m.type == 11 || m.type == 8) {
                // m is mario
                continue;
            }
            double dx = m.x-mario.x;
            double dy = m.y-mario.y;
            if (m.type == 6 || m.type == 7){
                if (dx < -3 && dx > 3){
                    cur_state[BONUS_ITEM_FAR] = true;
//                    System.out.println("far");
                } else {
                    cur_state[BONUS_ITEM_NEAR] = true;
//                    System.out.println("near");
                }

            } else {

                if (dx > -2 && dx < 6)
                    cur_state[MONSTER_NEAR] = true;
                else if (dx > 3 && dx < 12)
                  cur_state[MONSTER_FAR] = true;
                if (dy > 1 && dy < 15 && dx > 0)
                    cur_state[MONSTER_ABOVE] = true;
                else if (dy < -1 && dy > -15 && dx > 0)
                    cur_state[MONSTER_BELOW] = true;
            }
        }

		if (explorationProb != 0) {

			double biggest_value = Double.NEGATIVE_INFINITY;
			int biggest_value_itr = 0;
			double val;

			int forLength = policy_table[getIndexOfPolicyTable(cur_state)].length;
			for (int itr = 0; itr < forLength; itr++) {
			    val = policy_table[getIndexOfPolicyTable(cur_state)][itr];


				if (val > biggest_value) {
					biggest_value_itr = itr;
					biggest_value = policy_table[getIndexOfPolicyTable(cur_state)][itr];
				}
			}
            int[] cur_action = getActionFromActionIndex(biggest_value_itr);

            act.intArray = cur_action;

		}

		else {
			Random rand = new Random();
			walk_hesitation = Math.random();
			jump_hesitation = Math.random();
			act.intArray[0] = rand_.nextInt(3) - 1;
			act.intArray[1] = rand_.nextInt(2);
			act.intArray[2] = rand.nextInt(2);
		}

		int state_index = getIndexOfPolicyTable(cur_state);
//
//		private static final int MONSTER_NEAR = 0;
//	private static final int MONSTER_FAR = 1;
//	private static final int MONSTER_ABOVE = 2;
//	private static final int MONSTER_BELOW = 3;
//	private static final int PIT = 4;
//	private static final int PIPE = 5;
//	private static final int BREAKABLE_BLOCK = 6;
//	private static final int QUESTION_BLOCK = 7;
//	private static final int BONUS_ITEM = 8;
//	private static final int SOFT_POLICY = 5;

//		System.out.println("M_NEAR\tM_FAR\tM_ABV\tM_BEL\tPIT\tB_BLO\t?_BLO\tB_ITM\tstate_index: " + state_index);
//		System.out.println(cur_state[0] + "\t" + cur_state[1] + "\t" + cur_state[2] + "\t" + cur_state[3] + "\t" + cur_state[4] + "\t" + cur_state[5] + "\t" + cur_state[6] + "\t" + cur_state[7] + "\t" + cur_state[8] + "\t");


		int action_index = findActionCol.get(convertForFindActionCol(act.intArray[0], act.intArray[1], act.intArray[2]));
		state_vector.add(state_index);
		action_vector.add(action_index);

		//add the action to the trajectory being recorded, so it can be reused next trial
		this_actions.add(act);

		getActionDebugOut(last_actions.size() > step_number, explorationProb, act);
		return act;
	}

	private void constructorDebugOut(){
	    debug_out.println("{\n\t\"episodes\":[");
	}

	private void agentStartDebugOutBeforeAction(){
	    if (episode_num != 0){
		    debug_out.println(",");
		}
	    debug_out.println(indent(1) + "{");
		debug_out.println(indent(2) + "\"episode\": " + episode_num + ",");
		debug_out.println(indent(2) + "\"steps\":[");
		debug_out.println(indent(2) + "{");
        debug_out.println(indent(3) + "\"step_number\": " + step_number + ",");
		debug_out.println(indent(3) + "\"total_steps\": " + total_steps+ ",");
		debug_out.println(indent(3) + "\"total_reward\": " + total_reward + ",");
		debug_out.println(indent(3) + "\"actions_to_perform\": {");
	}

	private void agentStartDebugOutAfterAction(){
	    debug_out.println("\t\t\t}");
        debug_out.print("\t\t}");
	}

	private void getActionDebugOut(Boolean truncated_action, int explorationProb, Action act){
        debug_out.println(indent(4) + "\"last_actions.size() > step_number\": " + truncated_action + ",");
        debug_out.println(indent(4) + "\"explorationProb\": " + explorationProb + ",");
        debug_out.println(indent(4) + "\"a.intArray[0]\": " + act.intArray[0] + ",");
        debug_out.println(indent(4) + "\"a.intArray[1]\": " + act.intArray[1] + ",");
        debug_out.println(indent(4) + "\"a.intArray[2]\": " + act.intArray[2] + ",");
        // debug_out.println(indent(4) + "\"cur_state[MONSTER]\": " + cur_state[MONSTER] + ",");
//        debug_out.println(indent(4) + "\"cur_state[PIT]\": " + cur_state[PIT] + ",");
//        debug_out.println(indent(4) + "\"cur_state[PIPE]\": " + cur_state[PIPE] + ",");
//        debug_out.println(indent(4) + "\"cur_state[BREAKABLE_BLOCK]\": " + cur_state[BREAKABLE_BLOCK] + ",");
        debug_out.println(indent(4) + "\"cur_state[QUESTION_BLOCK]\": " + cur_state[QUESTION_BLOCK] + ",");
//        debug_out.println(indent(4) + "\"cur_state[BONUS_ITEM]\": " + cur_state[BONUS_ITEM]);
	}

	private void agentStepDebugOutStart(double r){
	    debug_out.println(",\n" + indent(2) + "{");
	    debug_out.println(indent(3) + "\"step_number\": " + step_number + ",");
		debug_out.println(indent(3) + "\"delta_reward\": " + r + ",");
		debug_out.println(indent(3) + "\"episode_reward\": " + episode_reward + ",");
		debug_out.println(indent(3) + "\"total_reward\": " + total_reward + ",");
		debug_out.println(indent(3) + "\"actions_to_perform\": {");
	}

	private void agentStepDebugOutEnd(Action a){
	    debug_out.println(indent(3) + "},");
		debug_out.println(indent(3) + "\"action_vector_num\": " + findActionCol.get(convertForFindActionCol(a.intArray[0], a.intArray[1], a.intArray[2])));
        debug_out.print(indent(2) + "}");
	}

	private void agentEndDebugOut(){
	    long time_passed = new Date().getTime()-trial_start;
	    debug_out.println("\n\t\t],");
        debug_out.println(indent(2) + "\"episode_reward\": " + episode_reward + ",");
        debug_out.println(indent(2) + "\"total_reward\": " + total_reward+ ",");
        debug_out.println(indent(2) + "\"episode_steps\": " + step_number + ",");
        debug_out.println(indent(2) + "\"total_steps\": " + total_steps + ",");
        debug_out.println(indent(2) + "\"steps_per_second\": " + (1000.0 * step_number / time_passed));
	}

	private void cleanupDebugOut(){
	    debug_out.println("\n]}");
	}

	private String indent(int numOfTabsToAdd){
	    String result = "";
	    for (int i = 0; i < numOfTabsToAdd; i++){
	        result += "\t";
	    }
	    return result;
	}

	private void importTablesFromFile(){
	    Scanner s = null;

        try{
            s = new Scanner(new File(export_filename));
	    } catch (Exception e){
	        System.out.println("File not found: " + export_filename + ". No previous learning to import.");
	        return;
	    }
	    System.out.println("Using previous learning from " + export_filename + ".");
        System.out.println("Reading from file.");

	    String jsonStr = "";
        s.useDelimiter("\\Z");
        jsonStr += s.next();
	    s.close();
	    System.out.println("Parsing json.");
        JSONObject j_obj = new JSONObject(jsonStr);

//	    // Read reward_vector
//	    JSONArray rv_ja = j_obj.getJSONArray("reward_vector");
//	    for (int i = 0; i < rv_ja.length(); i++){
//	        reward_vector.add(rv_ja.getDouble(i));
//	    }
//	    System.out.println("Read " + rv_ja.length() + " elements from " + export_filename + " to reward_vector.");
//
//	    // Read state_vector
//	    rv_ja = j_obj.getJSONArray("state_vector");
//	    for (int i = 0; i < rv_ja.length(); i++){
//	        state_vector.add(rv_ja.getInt(i));
//	    }
//	    System.out.println("Read " + rv_ja.length() + " elements from " + export_filename + " to state_vector.");
//
//	    // Read action_vector
//	    rv_ja = j_obj.getJSONArray("action_vector");
//	    for (int i = 0; i < rv_ja.length(); i++){
//	        action_vector.add(rv_ja.getInt(i));
//	    }
//	    System.out.println("Read " + rv_ja.length() + " elements from " + export_filename + " to action_vector.");

//	    // Read num_of_times_states_visited
//	    JSONArray stv_ja = j_obj.getJSONArray("num_of_times_states_visited");
//	    for (int i = 0; i < stv_ja.length(); i++){
//	        num_of_times_states_visited[i]= stv_ja.getInt(i);
//	    }
//	    System.out.println("Read " + stv_ja.length() + " elements from " + export_filename + " to num_of_times_states_visited.");

        // Read total_reward
        total_reward = j_obj.getDouble("total_reward");

	    // Read policy_table
	    JSONArray pt_ija = j_obj.getJSONArray("policy_table");
	    for (int i = 0; i < pt_ija.length(); i++){
	        JSONArray pt_jja = pt_ija.getJSONArray(i);
	        for (int j = 0; j < pt_jja.length(); j++){
	            policy_table[i][j] = pt_jja.getDouble(j);
	        }
	    }
	    System.out.println("Read " + (pt_ija.length() * pt_ija.getJSONArray(0).length()) + " elements from " + export_filename + " to policy_table.");

	     // Read reward_table
	    pt_ija = j_obj.getJSONArray("reward_table");
	    for (int i = 0; i < pt_ija.length(); i++){
	        JSONArray pt_jja = pt_ija.getJSONArray(i);
	        for (int j = 0; j < pt_jja.length(); j++){
	            reward_table[i][j] = pt_jja.getDouble(j);
	        }
	    }
	    System.out.println("Read " + (pt_ija.length() * pt_ija.getJSONArray(0).length()) + " elements from " + export_filename + " to reward_table.");

	    // Read policy_itr_table
	    JSONArray pit_ija = j_obj.getJSONArray("policy_itr_table");
	    for (int i = 0; i < pit_ija.length(); i++){
	        JSONArray pt_jja = pit_ija.getJSONArray(i);
	        for (int j = 0; j < pt_jja.length(); j++){
	            policy_itr_table[i][j] = pt_jja.getInt(j);
	        }
	    }
	    System.out.println("Read " + (pit_ija.length() * pit_ija.getJSONArray(0).length()) + " elements from " + export_filename + " to policy_itr_table.");
	}

	private void exportTablesToFile(){
	    System.out.println("Exporting vectors and tables to file.");

        PrintWriter export = null;
        Boolean notBeginning = false;
        try {
            export = new PrintWriter("ref_export.json", "UTF-8");
        } catch (Exception e){
            System.out.println("IO Error exporting.");
            return;
        }
        export.println(indent(0) + "{");

        // Export reward_vector
//        notBeginning = false;
//        export.println(indent(1) + "\"reward_vector\":");
//        export.println(indent(1) + "[");
//        for (double rw_i : reward_vector){
//            if (notBeginning){
//                export.println(",");
//            }
//            export.print(indent(2) + rw_i);
//            notBeginning = true;
//        }
//        export.println("\n" + indent(1) + "],");
//        System.out.println("Wrote " + reward_vector.size() + " elements from reward_vector to " + export_filename + ".");

        // Export state_vector
//        notBeginning = false;
//        export.println(indent(1) + "\"state_vector\":");
//        export.println(indent(1) + "[");
//        for (int rw_i : state_vector){
//            if (notBeginning){
//                export.println(",");
//            }
//            export.print(indent(2) + rw_i);
//            notBeginning = true;
//        }
//        export.println("\n" + indent(1) + "],");
//        System.out.println("Wrote " + state_vector.size() + " elements from state_vector to " + export_filename + ".");

        // Export action_vector
//        notBeginning = false;
//        export.println(indent(1) + "\"action_vector\":");
//        export.println(indent(1) + "[");
//        for (int rw_i : action_vector){
//            if (notBeginning){
//                export.println(",");
//            }
//            export.print(indent(2) + rw_i);
//            notBeginning = true;
//        }
//        export.println("\n" + indent(1) + "],");
//        System.out.println("Wrote " + action_vector.size() + " elements from action_vector to " + export_filename + ".");

        // Export num_of_times_states_visited
//        notBeginning = false;
//        export.println(indent(1) + "\"num_of_times_states_visited\":");
//        export.println(indent(1) + "[");
//        for (int sv_i : num_of_times_states_visited){
//            if (notBeginning){
//                export.println(",");
//            }
//            export.print(indent(2) + sv_i);
//            notBeginning = true;
//        }
//        export.println("\n" + indent(1) + "],");
//        System.out.println("Wrote " + num_of_times_states_visited.length + " elements from num_of_times_states_visited to " + export_filename + ".");

        // Export total_reward
        export.println(indent(1) + "\"total_reward\": " + total_reward + ",");
        System.out.println("Wrote " + reward_vector.size() + " elements from total_reward to " + export_filename + ".");

        // Export policy_table
        notBeginning = false;
        export.println(indent(1) + "\"policy_table\":");
        export.println(indent(1) + "[");
        Boolean notBeginning2;
        for (double[] pt_i : policy_table){
            if (notBeginning){
                export.println(",");
            }
            export.print(indent(2) + "[");
            notBeginning2 = false;
            for (double pt_j : pt_i){
                if (notBeginning2){
                    export.print(", ");
                }
                export.print(pt_j);
                notBeginning2 = true;
            }
            export.print("]");
            notBeginning = true;
        }
        export.println("\n" + indent(1) + "],");
        System.out.println("Wrote " + policy_table.length * policy_table[0].length + " elements from policy_table to " + export_filename + ".");



        // Export reward_table
        notBeginning = false;
        export.println(indent(1) + "\"reward_table\":");
        export.println(indent(1) + "[");
        for (double[] pt_i : reward_table){
            if (notBeginning){
                export.println(",");
            }
            export.print(indent(2) + "[");
            notBeginning2 = false;
            for (double pt_j : pt_i){
                if (notBeginning2){
                    export.print(", ");
                }
                export.print(pt_j);
                notBeginning2 = true;
            }
            export.print("]");
            notBeginning = true;
        }
        export.println("\n" + indent(1) + "],");
        System.out.println("Wrote " + reward_table.length * reward_table[0].length + " elements from reward_table to " + export_filename + ".");

        //Export policy_itr_table
        notBeginning = false;
        export.println(indent(1) + "\"policy_itr_table\":");
        export.println(indent(1) + "[");
        for (int[] pt_i : policy_itr_table){
            if (notBeginning){
                export.println(",");
            }
            export.print(indent(2) + "[");
            notBeginning2 = false;
            for (int pt_j : pt_i){
                if (notBeginning2){
                    export.print(", ");
                }
                export.print(pt_j);
                notBeginning2 = true;
            }
            export.print("]");
            notBeginning = true;
        }
        export.println("\n" + indent(1) + "]");
        System.out.println("Wrote " + policy_itr_table.length * policy_itr_table[0].length + " elements from policy_itr_table to " + export_filename + ".");


        export.println(indent(0) + "}");
        export.close();
	}

	public static void main(String[] args) {
		new AgentLoader(new ExMarioAgent()).run();
	}
}
