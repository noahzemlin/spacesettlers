package blac8074;

import java.awt.Color;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.nio.file.Files;
import java.nio.file.Paths;

import spacesettlers.actions.*;
import spacesettlers.clients.ImmutableTeamInfo;
import spacesettlers.clients.TeamClient;
import spacesettlers.graphics.*;
import spacesettlers.objects.*;
import spacesettlers.objects.powerups.*;
import spacesettlers.objects.resources.*;
import spacesettlers.objects.weapons.*;
import spacesettlers.simulator.Toroidal2DPhysics;
import spacesettlers.utilities.Position;
import spacesettlers.utilities.Vector2D;

/**
 * A single ship team that stays buzzy by collecting beacons and cores and shooting at nearby enemies
 * If it happens to accidently collect resources, it buys new bases and energy upgrades
 * 
 */

public class BeehaviorTeamClient extends TeamClient {
	// Size of each square in the grid (40 is the max int that works, 10 kinda works but lags, higher/lower values untested)
	static final double GRID_SIZE = 20;
	// If true, pathfinding will use A*, if false, pathfinding will use other method (hill climbing)
	static final boolean A_STAR = true;
	// The ship's path updates every this many timesteps
	static final int PATH_UPDATE_INTERVAL = 10;
	// Whether or not to draw graphics for debugging pathfinding
	static final boolean DEBUG_GRAPHICS = false;
	// Whether or not to draw graphics for debugging learning
	final boolean LEARNING_GRAPHICS = false;
	// Whether the agent should be learning using a GA
	final boolean GA_LEARNING = true;
	
	// Graph to store nodes that represent the environment
	BeeGraph graph;
	
	//For storing path graphics so we only have to create them when a new path is generated
	HashSet<SpacewarGraphics> pathGraphics;
	// Store grid graphics so we don't have to re-draw it repeatedly
	HashSet<SpacewarGraphics> gridGraphics;
	// Intended to store the paths of multiple ships, more useful for future projects
	HashMap<Ship, ArrayList<BeeNode>> paths;
	// Keeps track of targets that each ship is assigned to, more useful with multiple ships
	HashMap<Ship, AbstractObject> targets;
	// Stores bee pursuit graphics
	HashSet<SpacewarGraphics> bpGraphics;
	// Graphics for showing learned ship targeting
	HashSet<SpacewarGraphics> enemyTargetGraphics;
	// Bee Pursuit - used to move along paths
	BeePursuit bp;
	// Ship that is being targeted - used so we know which ship we're shooting at
	Ship targetShip;


	// The Bees!
	BeePopulation bees;
	// Which bee are we lookin at
	BeeChromosome currBee;
	// Number of ticks to evaulate a bee
	//int numBeeEvalTicks = 250;
	//int currBeeEvalTicks = 0;
	double currScore = 0;
	double lastDamage = 0;
	
	// Number of bees in each generation
	int generationSize = 40;
	// Current generation number
	int currGen;
	// Number of the current individual
	int individualNum;
	// Stats for the current bee
	String[] beeData;
	// Path to the file for the current generation
	String genFilePath;
	// Path to the file keeping track of the individual number
	// TODO: is there some way to set the contents of the file to "0" when the ladder starts?
	String individualNumPath;
	
	String teamName = "Bee Beehavior (Black and Zemlin)";
	
	@Override
	public void initialize(Toroidal2DPhysics space) {
		if (GA_LEARNING) {
			BufferedReader fileIn = null;
			BufferedWriter fileOut = null;
			individualNumPath = "blac8074/individualNumber.txt";
			try {
				// Get the number of the current individual
				fileIn = new BufferedReader(new FileReader(individualNumPath));
				individualNum = Integer.parseInt(fileIn.readLine());
				fileIn.close();
				
				// Update individual number in data file
				fileOut = new BufferedWriter(new FileWriter(individualNumPath));
				fileOut.write(Integer.toString(individualNum + 1));
				fileOut.close();
				
				// Calculate current generation
				currGen = individualNum / generationSize;
				
				// Path to file that contains (or will contain) info for current generation
				genFilePath = "blac8074/gen" + currGen +".csv";
				
				// If it is time for a new generation
				if ((individualNum % generationSize) == 0) {
					// Create new generation
					if (currGen == 0) {
						// Create initial random generation
						bees = new BeePopulation(generationSize);
					}
					else {
						// Create generation based on previous generation
						Bee[] beeArr = new Bee[generationSize];
						fileIn = new BufferedReader(new FileReader("blac8074/gen" + (currGen - 1) + ".csv"));
						for (int i = 0; i < generationSize; i++) {
							String line = fileIn.readLine();
							beeData = line.split(",");
							beeArr[i] = new Bee();
							beeArr[i].chromosome = new BeeChromosome();
							beeArr[i].chromosome.translationalKp = Double.parseDouble(beeData[0]);
							beeArr[i].chromosome.rotationalKp = Double.parseDouble(beeData[1]);
							beeArr[i].chromosome.lowEnergyThresh = Integer.parseInt(beeData[2]);
							beeArr[i].chromosome.shootEnemyDist = Double.parseDouble(beeData[3]);
							beeArr[i].score = Float.parseFloat(beeData[beeData.length - 1]);
						}
						fileIn.close();
						bees = new BeePopulation(beeArr);
						bees.createNewGeneration();
					}
					// Save new generation to file
					fileOut = new BufferedWriter(new FileWriter(genFilePath));
					fileOut.write(bees.toString());
					fileOut.close();
				}
		
				// Read data for current individual
				fileIn = new BufferedReader(new FileReader(genFilePath));
				int currLineNum = 0;
				String currLine = "";
				while (currLineNum <= (individualNum % generationSize)) {
					currLine = fileIn.readLine();
					++currLineNum;
				}
				fileIn.close();
				beeData = currLine.split(",");
				
				currBee = new BeeChromosome();
				currBee.translationalKp = Double.parseDouble(beeData[0]);
				currBee.rotationalKp = Double.parseDouble(beeData[1]);
				currBee.lowEnergyThresh = Integer.parseInt(beeData[2]);
				currBee.shootEnemyDist = Double.parseDouble(beeData[3]);
			}
			catch (IOException e) {
				e.printStackTrace();
			}
		}
		else {
			// Values to use when not learning
			// Learned values from generation 13
			// dGainVel isn't currently being used
			currBee = new BeeChromosome();
			currBee.translationalKp = 14;
			currBee.rotationalKp = 30.0;
			currBee.lowEnergyThresh = 1515;
			currBee.shootEnemyDist = 453;
		}
		
		// Number of grid squares in x dimension
		int numSquaresX = space.getWidth() / (int)GRID_SIZE;
		// Number of grid squares in y dimension
		int numSquaresY = space.getHeight() / (int)GRID_SIZE;
		graph = new BeeGraph(numSquaresX * numSquaresY, numSquaresY, numSquaresX, GRID_SIZE);
		BeeNode node;
		for (int i = 0; i < graph.getSize(); i++) {
			int x = i % numSquaresX;
			int y = i / numSquaresX;
			Position nodePos = new Position(x * GRID_SIZE + GRID_SIZE / 2, y * GRID_SIZE + GRID_SIZE / 2);
			node = new BeeNode(nodePos);
			graph.addNode(i, node);
			//System.out.println("Created node " + i +  " at: " + node.getPosition());
		}

		// Add adjacent nodes to each node
		for (int i = 0; i < graph.getSize(); i++) {
			double distance;
			int[] adjacent = graph.findAdjacentIndices(i);
			node = graph.getNode(i);
			// Add edge costs for each adjacent node
			for (int j = 0; j < adjacent.length; j++) {
				if (j < 4) {
					distance = GRID_SIZE;
				}
				else {
					// a == b => c = sqrt(a^2 + b^2) = sqrt(2 * a^2) = sqrt(2 * a * a)
					distance = Math.sqrt(2 * GRID_SIZE * GRID_SIZE); 
				}
				node.addAdjacent(graph.getNode(adjacent[j]), distance);
				//System.out.println("Add adjacent node " + adjacent[j] + " to node " + i + " with distance " + distance);
			}
		}
		pathGraphics = new HashSet<SpacewarGraphics>();
		gridGraphics = drawGrid(new Position(0, 0), GRID_SIZE, 1080, 1600, Color.GRAY);
		bp = new BeePursuit();
		paths = new HashMap<Ship, ArrayList<BeeNode>>();
		bpGraphics = new HashSet<>();
		targets = new HashMap<Ship, AbstractObject>();
		enemyTargetGraphics = new HashSet<SpacewarGraphics>();

		//bees = new BeePopulation(80);
		//currBee = bees.getNextBee(currScore);
	}

	@Override
	public void shutDown(Toroidal2DPhysics space) {
		// TODO: set score here?
		if (GA_LEARNING) {
			// Look through the info for all teams
			ImmutableTeamInfo teamInfo = null;
			for (ImmutableTeamInfo info : space.getTeamInfo()) {
				// Info for our team
				if (info.getTeamName().equalsIgnoreCase(teamName)) {
					// TODO: replace + 3000 * (info.getScore() - info.getTotalKillsInflicted() - info.getTotalCoresCollected()) with -3000 * getTotalKillsReceived
					currScore = info.getTotalDamageInflicted() - info.getTotalDamageReceived() + 3000 * (info.getScore() - info.getTotalKillsInflicted() - info.getTotalCoresCollected());
					teamInfo = info;
					break;
				}
			}
			try {
				// Write score for individual to generation file
				List<String> lines = new ArrayList<>(Files.readAllLines(Paths.get(genFilePath)));
				lines.set(individualNum % generationSize, lines.get(individualNum % generationSize) + teamInfo.getScore() + "," + currScore);
				Files.write(Paths.get(genFilePath), lines);
			} 
			catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	@Override
	public Map<UUID, AbstractAction> getMovementStart(Toroidal2DPhysics space,
			Set<AbstractActionableObject> actionableObjects) {
		HashMap<UUID, AbstractAction> actions = new HashMap<UUID, AbstractAction>();		
		Ship ship = null;
		for (AbstractActionableObject actionable :  actionableObjects) {
			if (actionable instanceof Ship) {
				ship = (Ship) actionable;

				//double deltaDmg = ship.getDamageReceived() - lastDamage;
				//lastDamage = ship.getDamageReceived();

				//currScore += deltaDmg / 2;
				break;
			}
		}
		
		/*
		if (currBeeEvalTicks % numBeeEvalTicks == 0) {
			currBee = bees.getNextBee(currScore);
			currScore = 0;
		}

		currBeeEvalTicks++;
		*/

		// Update obstructions frequently to make the debug graphics more responsive
		if (DEBUG_GRAPHICS) {
			// Update obstructions of first half of graph on even timesteps
			if ((space.getCurrentTimestep() & 1) == 0) {
				findObstructions(ship.getRadius(), space, ship.getTeamName(), 0, graph.getSize() / 2 - 1);
			}
			// Update obstructions of last half of graph on odd timesteps
			else {
				findObstructions(ship.getRadius(), space, ship.getTeamName(), graph.getSize() / 2, graph.getSize() - 1);
			}
		}
		// Update obstructions only once before calculating new path
		else {
			// Update obstructions of first half of graph 1 timestep before the path is calculated
			if ((space.getCurrentTimestep() % PATH_UPDATE_INTERVAL) == (PATH_UPDATE_INTERVAL - 1)) {
				findObstructions(ship.getRadius(), space, ship.getTeamName(), 0, graph.getSize() / 2 - 1);
			}
			// Update obstructions of last half two timesteps before the path is calculated
			else if ((space.getCurrentTimestep() % PATH_UPDATE_INTERVAL) == (PATH_UPDATE_INTERVAL - 2)) {
				findObstructions(ship.getRadius(), space, ship.getTeamName(), graph.getSize() / 2, graph.getSize() - 1);
			}
		}
		
		for (AbstractObject actionable :  actionableObjects) {
			// Assign actions to living ships
			if ((actionable instanceof Ship) && actionable.isAlive()) {
				ship = (Ship) actionable;
				Position currentPosition = ship.getPosition();
				// Find new path every so many timesteps
				if ((space.getCurrentTimestep() % PATH_UPDATE_INTERVAL) == 0) {
					ArrayList<BeeNode> path;
					AbstractObject target = findTarget(ship, space);
					// If we have a valid target
					if (target != null) {
						targets.put(ship, target);
						Position targetPos = target.getPosition();
						// Avoid crashing into the target ship
						if (target instanceof Ship) {
							if (space.findShortestDistance(ship.getPosition(), target.getPosition()) < (3 * ship.getRadius())) {
								targetPos = ship.getPosition();
							}
						}
						else {
							// Aim ahead of a moving target
							if (target.isMoveable()) {
								targetPos = new Position(targetPos.getX() + targetPos.getxVelocity(), targetPos.getY() + targetPos.getyVelocity());
								space.toroidalWrap(targetPos);
							}
						}
						
						// Generate path using A* algorithm
						if (A_STAR) {
							path = graph.getAStarPath(positionToNodeIndex(currentPosition), positionToNodeIndex(targetPos));
						}
						// Generate path using other algorithm (hill climbing)
						else {
							path = graph.getHillClimbingPath(positionToNodeIndex(currentPosition), positionToNodeIndex(targetPos));
						}
						// Create new path graphics
						if (DEBUG_GRAPHICS) {
							pathGraphics.clear();
							// Add green circles representing nodes on the path
							for (BeeNode node : path) {
								pathGraphics.add(new CircleGraphics((int)GRID_SIZE / 8, Color.GREEN, node.getPosition()));
							}
						}
						bp.setPath(path);
						paths.put(ship, path);		
					}
					else {
						actions.put(actionable.getId(), new DoNothingAction());
					}
				}

				// Always make a move based on last path

				// TODO: Handle multiple agents in the future (multiple BeePursuits?)
				double radius = 2.0 * GRID_SIZE;
				bpGraphics.clear();
				Position goalPos = bp.getDesiredPosition(space, ship.getPosition(), radius);

				// Expand radius if we dont find anything
				int iters = 0;
				while (goalPos == null && iters < 20) {
					iters++;
					radius *= 1.25;
					goalPos = bp.getDesiredPosition(space, ship.getPosition(), radius);
				}

				// If we still couldn't find path
				if (iters >= 20) {
					actions.put(actionable.getId(), new DoNothingAction());
					continue;
				}

				// Add a target graphic at our goal position
				bpGraphics.add(new TargetGraphics(16, Color.PINK, goalPos));
				
				targetShip = null;
				// If we are low on energy or chasing a core, don't find an enemy ship to target
				if ((ship.getEnergy() > currBee.lowEnergyThresh) && !(targets.get(ship) instanceof AiCore)) {
					// Find the closest enemy ship within a certain (learned) distance
					double shipDistance = currBee.shootEnemyDist;
					for (Ship otherShip : space.getShips()) {
						// If the other ship is an enemy ship
						if (!otherShip.getTeamName().equals(ship.getTeamName())) {
							// Get distance without toroidal wrap because toroidal shooting is hard
							double distance = new Vector2D(currentPosition).subtract(new Vector2D(otherShip.getPosition())).getMagnitude();
							if (distance < shipDistance) {
								targetShip = otherShip;
								shipDistance = distance;
							}
						}
					}
					if (targetShip != null) {
						enemyTargetGraphics.add(new TargetGraphics(16, Color.WHITE, targetShip.getPosition()));
					}
					enemyTargetGraphics.add(new CircleGraphics((int)currBee.shootEnemyDist, Color.WHITE, ship.getPosition()));
				}
				MoveActionWithOrientation action;
				// If there's no ship to target, don't worry about orientation
				if (targetShip == null) {
					action = new MoveActionWithOrientation(space, ship.getPosition(), goalPos);
					action.setKpRotational(0.0);
					action.setKvRotational(0.0);
				}
				// If there is an enemy ship to target, point at the enemy ship
				else {
					Position targetShipPos = targetShip.getPosition();
					goalPos.setOrientation(new Vector2D(targetShipPos.getX() - currentPosition.getX(),  
							targetShipPos.getY() - currentPosition.getY()).getAngle());
					action = new MoveActionWithOrientation(space, ship.getPosition(), goalPos);
					action.setKpRotational(currBee.rotationalKp);
					action.setKvRotational(2 * Math.sqrt(currBee.rotationalKp));
				}

				action.setKpTranslational(currBee.translationalKp);
				action.setKvTranslational(2 * Math.sqrt(currBee.translationalKp));

				//currScore += ship.getPosition().getTranslationalVelocity().getMagnitude() / 100f;

				actions.put(actionable.getId(), action);
			}
			else {
				actions.put(actionable.getId(), new DoNothingAction());
			}
		}
		return actions;
	}

	@Override
	public void getMovementEnd(Toroidal2DPhysics space, Set<AbstractActionableObject> actionableObjects) {
	}

	@Override
	public Set<SpacewarGraphics> getGraphics() {
		HashSet<SpacewarGraphics> graphics = new HashSet<SpacewarGraphics>();
		if (DEBUG_GRAPHICS) {
			// Draw grid on screen
			graphics.addAll(gridGraphics);
			// Draw red circles representing each obstructed node
			for (int i = 0; i < graph.getSize(); i++) {
				if (graph.getNode(i).getObstructed()) {
					graphics.add(new CircleGraphics((int)GRID_SIZE / 8, Color.RED, graph.getNode(i).getPosition()));
				}
			}
			// Add the graphics representing the generated path
			graphics.addAll(pathGraphics);
			// Add graphics showing where on the path we are aiming
			graphics.addAll(bpGraphics);
		}
		if (LEARNING_GRAPHICS) {
			// Add graphics showing shootEnemyDist and which enemy (if any) is being targeted
			graphics.addAll(enemyTargetGraphics);
		}
		enemyTargetGraphics.clear();
		return graphics;
	}


	@Override
	public Map<UUID, PurchaseTypes> getTeamPurchases(Toroidal2DPhysics space,
			Set<AbstractActionableObject> actionableObjects, 
			ResourcePile resourcesAvailable, 
			PurchaseCosts purchaseCosts) {

		HashMap<UUID, PurchaseTypes> purchases = new HashMap<UUID, PurchaseTypes>();
		double BASE_BUYING_DISTANCE = 400;
		
		// Buy a double max energy powerup if we can afford it
		if (purchaseCosts.canAfford(PurchaseTypes.POWERUP_DOUBLE_MAX_ENERGY, resourcesAvailable)) {
			for (AbstractActionableObject actionableObject : actionableObjects) {
				if (actionableObject instanceof Ship) {
					Ship ship = (Ship) actionableObject;
					purchases.put(ship.getId(), PurchaseTypes.POWERUP_DOUBLE_MAX_ENERGY);
				}
			}
		}
		
		// Buy a new base if we can afford it
		if (purchaseCosts.canAfford(PurchaseTypes.BASE, resourcesAvailable)) {
			for (AbstractActionableObject actionableObject : actionableObjects) {
				if (actionableObject instanceof Ship) {
					Ship ship = (Ship) actionableObject;
					Set<Base> bases = space.getBases();

					// how far away is this ship to a base of my team?
					double minDistance = Double.MAX_VALUE;
					for (Base base : bases) {
						if (base.getTeamName().equalsIgnoreCase(getTeamName())) {
							double distance = space.findShortestDistance(ship.getPosition(), base.getPosition());
							if (distance < minDistance) {
								minDistance = distance;
							}
						}
					}
					// Only buy a base if it's far enough from existing bases
					if (minDistance > BASE_BUYING_DISTANCE) {
						purchases.put(ship.getId(), PurchaseTypes.BASE);
						//System.out.println("Buying a base!!");
						break;
					}
				}
			}		
		}
		return purchases;
	}

	@Override
	public Map<UUID, SpaceSettlersPowerupEnum> getPowerups(Toroidal2DPhysics space,
			Set<AbstractActionableObject> actionableObjects) {
		
		HashMap<UUID, SpaceSettlersPowerupEnum> powerUps = new HashMap<UUID, SpaceSettlersPowerupEnum>();
		
		// If we have a power up for doubling max energy, use it
		for (AbstractActionableObject actionableObject : actionableObjects) {
			if (actionableObject instanceof Ship) {
				Ship ship = (Ship) actionableObject;
				if (ship.isValidPowerup(SpaceSettlersPowerupEnum.DOUBLE_MAX_ENERGY)) {
					powerUps.put(ship.getId(), SpaceSettlersPowerupEnum.DOUBLE_MAX_ENERGY);
				}
			}
		}
		
		// Potentially fire every 3 timesteps
		if ((targetShip != null) && ((space.getCurrentTimestep() % 3) == 0)) {
			for (AbstractActionableObject actionableObject : actionableObjects){
				if (actionableObject instanceof Ship) {
					Position shipPos = actionableObject.getPosition();
					Position targetShipPos = targetShip.getPosition();
					// Calculate the difference between the ship's current orientation and the orientation it needs to face the targeted ship
					double angleDiff = actionableObject.getPosition().getOrientation() - new Vector2D(new Position(targetShipPos.getX() - shipPos.getX(),  
							targetShipPos.getY() - shipPos.getY())).getAngle();
					angleDiff = (angleDiff + Math.PI) % (2 * Math.PI) - Math.PI;
					// If the ship is basically pointing at the targeted ship, fire missiles
					if (Math.abs(angleDiff) < 0.2) {
						SpaceSettlersPowerupEnum powerup = SpaceSettlersPowerupEnum.FIRE_MISSILE;
						powerUps.put(actionableObject.getId(), powerup);
					}
				}
			}
		}
		
		return powerUps;
	}
	
	/*
	 * Find what object the ship should aim for
	 */
	public AbstractObject findTarget(Ship ship, Toroidal2DPhysics space) {
		AbstractObject targetObj = null;
		// We are low on energy -> go get energy
		if (ship.getEnergy() < currBee.lowEnergyThresh) {
			AbstractObject energy = pickNearestEnergy(space, ship);
			if (energy != null) {
				return energy;
			}
		}
		// We don't have a core -> check for cores
		if (ship.getNumCores() == 0) {
			AiCore core = pickNearestCore(space, ship);
			// There is a core -> go get it
			if (core != null) {
				return core;
			}
			// There isn't a core -> 
			else {
				// The ship is a little heavy (lots of resources) -> return to base
				if (ship.getMass() > 350) {
					Base base = pickNearestFriendlyBase(space, ship);
					if (base != null) {
						return base;
					}
				}
				// Chase an enemy ship
				Ship enemy = pickNearestEnemy(space, ship);
				if (enemy != null) {
					return enemy;
				}
			}
		} 
		// Return core to base
		else {
			AiCore core = pickNearestCore(space, ship);
			// There isn't another core or there is another core but we are low on energy -> go to base
			if ((core == null) || (ship.getEnergy() < currBee.lowEnergyThresh)) {
				Base base = pickNearestFriendlyBase(space, ship);
				if (base != null) {
					return base;
				}
			}
			// There are multiple cores and we have enough energy -> go get another core
			else {
				return core;
			}
		}
		return targetObj;
	}
	
	/*
	 * Find the nearest beacon or base with 2000 or more energy
	 */
	private AbstractObject pickNearestEnergy(Toroidal2DPhysics space, Ship ship) {
		// get the current beacons
		Set<Beacon> beacons = space.getBeacons();

		AbstractObject closestEnergy = null;
		double bestDistance = Double.POSITIVE_INFINITY;

		for (Beacon beacon : beacons) {
			double dist = space.findShortestDistance(ship.getPosition(), beacon.getPosition());
			if (dist < bestDistance) {
				bestDistance = dist;
				closestEnergy = beacon;
			}
		}
		
		for (Base base : space.getBases()) {
			if (base.getTeamName().equalsIgnoreCase(ship.getTeamName())) {
				if (base.getEnergy() >= 2000) {
					double dist = space.findShortestDistance(ship.getPosition(), base.getPosition());
					if (dist < bestDistance) {
						bestDistance = dist;
						closestEnergy = base;
					}
				}
			}
		}
		return closestEnergy;
	}
	
	/*
	 * Find the nearest friendly base
	 */
	private Base pickNearestFriendlyBase(Toroidal2DPhysics space, Ship ship) {
		Set<Base> bases = space.getBases();

		Base closestBase = null;
		double bestDistance = Double.POSITIVE_INFINITY;

		for (Base base : bases) {
			// Check if the base belongs to our team
			if (base.getTeamName().equalsIgnoreCase(ship.getTeamName())) {
				double dist = space.findShortestDistance(ship.getPosition(), base.getPosition());
				if (dist < bestDistance) {
					bestDistance = dist;
					closestBase = base;
				}
			}
		}
		return closestBase;
	}
	
	/*
	 * Find the nearest AiCore
	 */
	private AiCore pickNearestCore(Toroidal2DPhysics space, Ship ship) {
		Set<AiCore> cores = space.getCores();

		AiCore closestCore = null;
		double bestDistance = Double.POSITIVE_INFINITY;

		for (AiCore core : cores) {
			double dist = space.findShortestDistance(ship.getPosition(), core.getPosition());
			if (dist < bestDistance) {
				bestDistance = dist;
				closestCore = core;
			}
		}

		return closestCore;
	}
	
	/*
	 * Find the nearest enemy ship
	 */
	private Ship pickNearestEnemy(Toroidal2DPhysics space, Ship ship) {
		double bestDistance = Double.POSITIVE_INFINITY;
		Ship closestEnemy = null;
		for (Ship otherShip : space.getShips()) {
			// If the other ship is a living enemy ship
			if (!otherShip.getTeamName().equalsIgnoreCase(ship.getTeamName()) && otherShip.isAlive()) {
				// Get distance without toroidal wrap because toroidal shooting is hard
				double distance = space.findShortestDistance(ship.getPosition(), otherShip.getPosition());
				if (distance < bestDistance) {
					bestDistance = distance;
					closestEnemy = otherShip;
				}
			}
		}
		return closestEnemy;
	}
	
	/*
	 * Draws a grid (using lines) of squares of the given size, given the width and height of the grid,
	 * the position of the upper left corner of the grid, and the color of the grid lines
	 */
	private HashSet<SpacewarGraphics> drawGrid(Position startPos, double gridSize, int height, int width, Color color) {
		HashSet<SpacewarGraphics> grid = new HashSet<SpacewarGraphics>();
		LineGraphics line;
		// Add vertical lines
		for (int i = (int)startPos.getX(); i <= width + startPos.getX(); i += gridSize) {
			line = new LineGraphics(new Position(i, startPos.getY()), new Position(i, height + startPos.getY()), new Vector2D(0, height));
			line.setLineColor(color);
			grid.add(line);
		}
		// Add horizontal lines
		for (int i = (int)startPos.getY(); i <= height + startPos.getY(); i += gridSize) {
			line = new LineGraphics(new Position(startPos.getX(), i), new Position(width + startPos.getX(), i), new Vector2D(width, 0));
			line.setLineColor(color);
			grid.add(line);
		}
		return grid;
	}
	
	// Converts a position to the index of the grid square that contains that position
	private int positionToNodeIndex(Position position) {
		int x = (int)(position.getX() / GRID_SIZE);
		int y = (int)(position.getY() / GRID_SIZE);
		return x + y * graph.getWidth();
	}

	/*
	 * Check each grid square to see if it contains an obstacle or is near enough to an obstacle
	 * If it is, obstruct the corresponding node
	 * If not, unobstruct the corresponding node
	 * 
	 * Obstacles currently include: non-mineable asteroids, bases, enemy ships, weapon projectiles
	 */
	public void findObstructions(int radius, Toroidal2DPhysics space, String teamName, int startIndex, int stopIndex) {
		// Check the selected nodes for obstructions
		for (int nodeIndex = startIndex; nodeIndex <= stopIndex; nodeIndex++) {
			boolean obstructionFound = false;
			for (Asteroid asteroid : space.getAsteroids()) {
				// Check for non-mineable asteroids
				if (!asteroid.isMineable()) {
					if (space.findShortestDistanceVector(asteroid.getPosition(), graph.getNode(nodeIndex).getPosition())
							.getMagnitude() <= (radius + (2 * asteroid.getRadius()))) {
						graph.obstructNode(nodeIndex);
						obstructionFound = true;
						break;
					}
					// Check if asteroid is moving towards the current node
					if (asteroid.isMoveable()) {
						// Note: this may not completely work if the asteroid is moving very fast
						Position futurePos = new Position(asteroid.getPosition().getX() + asteroid.getPosition().getxVelocity(), 
								asteroid.getPosition().getY() + asteroid.getPosition().getyVelocity());
						if (space.findShortestDistanceVector(futurePos, graph.getNode(nodeIndex).getPosition())
								.getMagnitude() <= (radius + (2 * asteroid.getRadius()))) {
							graph.obstructNode(nodeIndex);
							obstructionFound = true;
							break;
						}
					}
				}
			}
			if (!obstructionFound) {
				// Check for bases
				for (Base base : space.getBases()) {
					// Don't obstruct around the base our ship is heading to
					if (!targets.containsValue(base)) {
						if (space.findShortestDistanceVector(base.getPosition(), graph.getNode(nodeIndex).getPosition())
								.getMagnitude() <= (radius + (2 * base.getRadius()))) {
							graph.obstructNode(nodeIndex);
							obstructionFound = true;
							break;
						}
					}
				}
			}
			if (!obstructionFound && (teamName != null)) {
				// Check for living enemy ships
				for (Ship ship : space.getShips()) {
					if (!ship.getTeamName().equals(teamName) && ship.isAlive()) {
						// If the enemy ship isn't being chased by our ship
						if (!targets.containsValue(ship)) {
							if (space.findShortestDistanceVector(ship.getPosition(), graph.getNode(nodeIndex).getPosition())
									.getMagnitude() <= (radius + (2 * ship.getRadius()))) {
								graph.obstructNode(nodeIndex);
								obstructionFound = true;
								break;
							}
						}
					}
				}
			}
			// Check for weapon projectiles
			if (!obstructionFound) {
				for (AbstractWeapon weapon : space.getWeapons()) {
					// Don't obstruct nodes based on our own bullets because otherwise ships get confused when they shoot in front of themselves
					if (!weapon.getFiringShip().getTeamName().equalsIgnoreCase(teamName)) {
						if (space.findShortestDistanceVector(weapon.getPosition(), graph.getNode(nodeIndex).getPosition())
								.getMagnitude() <= (radius + (2 * weapon.getRadius()))) {
							graph.obstructNode(nodeIndex);
							obstructionFound = true;
							break;
						}
					}
				}
			}
			// No obstructions were found for the current node
			if (!obstructionFound) {
				graph.unobstructNode(nodeIndex);
			}
		}
	}
}