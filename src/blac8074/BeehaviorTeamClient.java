package blac8074;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import spacesettlers.actions.*;
import spacesettlers.clients.TeamClient;
import spacesettlers.graphics.*;
import spacesettlers.objects.*;
import spacesettlers.objects.powerups.*;
import spacesettlers.objects.resources.*;
import spacesettlers.objects.weapons.*;
import spacesettlers.simulator.Toroidal2DPhysics;
import spacesettlers.utilities.Position;
import spacesettlers.utilities.Vector2D;

import blac8074.BeeGraph;


public class BeehaviorTeamClient extends TeamClient {
	BeeGraph graph;
	// Size of each square in the grid (40 is the max int that works, higher values untested)
	static double GRID_SIZE = 20;
	HashMap<AbstractObject, ArrayList<Integer>> obstacleMap;
	HashSet<SpacewarGraphics> pathGraphics;
	HashSet<SpacewarGraphics> gridGraphics;
	public static HashSet<SpacewarGraphics> bpGraphics;

	BeePursuit pp;

	@Override
	public void initialize(Toroidal2DPhysics space) {
		// Number of grid squares in x dimension
		int numSquaresX = space.getWidth() / (int)GRID_SIZE;
		// Number of grid squares in y dimension
		int numSquaresY = space.getHeight() / (int)GRID_SIZE;
		graph = new BeeGraph(numSquaresX * numSquaresY, numSquaresY, numSquaresX, GRID_SIZE);
		BeeNode node;
		Position position;
		for (int i = 0; i < graph.getSize(); i++) {
			int x = i % numSquaresX;
			int y = i / numSquaresX;
			position = new Position(x * GRID_SIZE + GRID_SIZE / 2, y * GRID_SIZE + GRID_SIZE / 2);
			node = new BeeNode(position);
			graph.addNode(i, node);
			//System.out.println("Created node " + i +  " at: " + node.getPosition());
		}

		int[] adjacent;
		double distance;
		// Add adjacent nodes to each node
		for (int i = 0; i < graph.getSize(); i++) {
			adjacent = graph.findAdjacentIndices(i);
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
		obstacleMap = new HashMap<AbstractObject, ArrayList<Integer>>();
		// Store grid graphics so we don't have to re-draw it repeatedly
		gridGraphics = drawGrid(new Position(0, 0), GRID_SIZE, 1080, 1600, Color.GRAY);
		bpGraphics = new HashSet<>();
		pp = new BeePursuit();
	}

	@Override
	public void shutDown(Toroidal2DPhysics space) {
		// TODO Auto-generated method stub

	}

	@Override
	public Map<UUID, AbstractAction> getMovementStart(Toroidal2DPhysics space,
			Set<AbstractActionableObject> actionableObjects) {
		HashMap<UUID, AbstractAction> actions = new HashMap<UUID, AbstractAction>();		
		ArrayList<Integer> nodeIndexList;
		ArrayList<Integer> unobstructList;
		// TODO: is there some other way to get our team name other than from a ship?
		Ship ship = null;
		for (AbstractObject actionable :  actionableObjects) {
			if (actionable instanceof Ship) {
				ship = (Ship) actionable;
				break;
			}
		}
		if (ship != null) {
			// Update obstructions of first half of graph on even timesteps
			if ((space.getCurrentTimestep() & 1) == 0) {
				findObstructions(ship.getRadius() / 2, space, ship.getTeamName(), 0, graph.getSize() / 2 - 1);
			}
			// Update obstructions of last half of graph on odd timesteps
			else {
				findObstructions(ship.getRadius() / 2, space, ship.getTeamName(), graph.getSize() / 2, graph.getSize() - 1);
			}
		}
		else {
			// Update obstructions of first half of graph on even timesteps
			if ((space.getCurrentTimestep() & 1) == 0) {
				findObstructions((int)GRID_SIZE / 4, space, null, 0, graph.getSize() / 2 - 1);
			}
			// Update obstructions of last half of graph on odd timesteps
			else {
				findObstructions((int)GRID_SIZE / 4, space, null, graph.getSize() / 2, graph.getSize() - 1);
			}
		}
		
		for (AbstractObject actionable :  actionableObjects) {
			if (actionable instanceof Ship) {
				ship = (Ship) actionable;
				Position currentPosition = ship.getPosition();
				// Find new path every 20 timesteps
				if ((space.getCurrentTimestep() % 20) == 0) {
					pathGraphics.clear();

					ArrayList<BeeNode> path = graph.getAStarPath(positionToNodeIndex(currentPosition), positionToNodeIndex(findTarget(ship, space).getPosition()));
					for (BeeNode node : path) {
						pathGraphics.add(new CircleGraphics((int)GRID_SIZE / 8, Color.GREEN, node.getPosition()));
					}

					pp.setPath(path);
				}

				// Always make a move based on last path

				// TODO: Handle multiple agents in the future (multiple BeePursuits?)
				double radius = 2.0 * GRID_SIZE;
				bpGraphics.clear();
				Position goalPos = pp.getDesiredPosition(space, ship.getPosition(), radius);

				if (goalPos == null) {
					actions.put(actionable.getId(), new DoNothingAction());
					continue;
				}

				// Expand radius if we dont find anything
				int iters = 0;
				while (goalPos == null && iters < 20) {
					iters++;
					radius *= 1.25;
					goalPos = pp.getDesiredPosition(space, ship.getPosition(), radius);
				}

				// If we still couldn't find path
				if (iters >= 20) {
					actions.put(actionable.getId(), new DoNothingAction());
					continue;
				}

				bpGraphics.add(new TargetGraphics(16, Color.PINK, goalPos));

				MoveAction action = new MoveAction(space, ship.getPosition(), goalPos);

				action.setKpRotational(30.0);
				action.setKvRotational(2.0 * Math.sqrt(30.0));
				action.setKpTranslational(14.0);
				action.setKvTranslational(2.2 * Math.sqrt(14.0));

				actions.put(actionable.getId(), action);
			} else {
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
		boolean DEBUG_GRAPHICS = true;
		
		HashSet<SpacewarGraphics> graphics = new HashSet<SpacewarGraphics>();
		if (DEBUG_GRAPHICS) {
			// TODO: find way(s) to reduce lag when drawing lots of objects
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

			graphics.addAll(bpGraphics);
		}
		return graphics;
	}


	@Override
	public Map<UUID, PurchaseTypes> getTeamPurchases(Toroidal2DPhysics space,
			Set<AbstractActionableObject> actionableObjects, 
			ResourcePile resourcesAvailable, 
			PurchaseCosts purchaseCosts) {
		// TODO Auto-generated method stub
		return new HashMap<UUID,PurchaseTypes>();
	}

	@Override
	public Map<UUID, SpaceSettlersPowerupEnum> getPowerups(Toroidal2DPhysics space,
			Set<AbstractActionableObject> actionableObjects) {
		// TODO Auto-generated method stub
		return null;
	}
	
	public AbstractObject findTarget(Ship ship, Toroidal2DPhysics space) {
		Set<Beacon> beacons = space.getBeacons();

		Beacon closestBeacon = null;
		double bestDistance = Double.POSITIVE_INFINITY;

		// Find the closest beacon
		for (Beacon beacon : beacons) {
			double dist = space.findShortestDistance(ship.getPosition(), beacon.getPosition());
			if (dist < bestDistance) {
				bestDistance = dist;
				closestBeacon = beacon;
			}
		}
		return closestBeacon;
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
	
	private ArrayList<Integer> objectToIndices(AbstractObject obj) {
		double x = obj.getPosition().getX();
		double y = obj.getPosition().getY();
		int radius = obj.getRadius();
		ArrayList<Integer> indices = new ArrayList<Integer>(4);
		// TODO: add more based on grid size, ship size?
		// Get position of corners of a square that contains the object - sometimes overestimates circular object's position
		indices.add(positionToNodeIndex(graph.toroidalWrap(new Position(x - radius, y + radius))));
		indices.add(positionToNodeIndex(graph.toroidalWrap(new Position(x + radius, y + radius))));
		indices.add(positionToNodeIndex(graph.toroidalWrap(new Position(x - radius, y - radius))));
		indices.add(positionToNodeIndex(graph.toroidalWrap(new Position(x + radius, y - radius))));
		
		return indices;
	}

	public void findObstructions(int radius, Toroidal2DPhysics space, String teamName, int startIndex, int stopIndex) {
		boolean obstructionFound;
		for (int nodeIndex = startIndex; nodeIndex <= stopIndex; nodeIndex++) {
			obstructionFound = false;
			for (Asteroid asteroid : space.getAsteroids()) {
				if (!asteroid.isMineable()) {
					// fixed bug where it only checked radius and not diameter
					if (space.findShortestDistanceVector(asteroid.getPosition(), graph.getNode(nodeIndex).getPosition())
							.getMagnitude() <= (radius + (2 * asteroid.getRadius()))) {
						graph.obstructNode(nodeIndex);
						obstructionFound = true;
						break;
					}
				}
			}
			if (!obstructionFound) {
				for (Base base : space.getBases()) {
					if (space.findShortestDistanceVector(base.getPosition(), graph.getNode(nodeIndex).getPosition())
							.getMagnitude() <= (radius + (2 * base.getRadius()))) {
						graph.obstructNode(nodeIndex);
						obstructionFound = true;
						break;
					}
				}
			}
			if (!obstructionFound && (teamName != null)) {
				for (Ship ship : space.getShips()) {
					if (!ship.getTeamName().equals(teamName)) {
						if (space.findShortestDistanceVector(ship.getPosition(), graph.getNode(nodeIndex).getPosition())
								.getMagnitude() <= (radius + (2 * ship.getRadius()))) {
							graph.obstructNode(nodeIndex);
							obstructionFound = true;
							break;
						}
					}
				}
			}
			if (!obstructionFound) {
				for (AbstractWeapon weapon : space.getWeapons()) {
					if (space.findShortestDistanceVector(weapon.getPosition(), graph.getNode(nodeIndex).getPosition())
							.getMagnitude() <= (radius + (2 * weapon.getRadius()))) {
						graph.obstructNode(nodeIndex);
						obstructionFound = true;
						break;
					}
				}
			}
			if (!obstructionFound) {
				// TODO: Check more object types
			}
			if (!obstructionFound) {
				graph.unobstructNode(nodeIndex);
			}
		}
	}
}
