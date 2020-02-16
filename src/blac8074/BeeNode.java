package blac8074;

import java.util.HashMap;

import spacesettlers.utilities.*;

public class BeeNode implements Comparable<BeeNode>{
	private Position position;
	private boolean obstructed;
	private double totalCost;
	private HashMap<BeeNode, Double> adjacencyMap;
	
	public BeeNode() {
		adjacencyMap = new HashMap<BeeNode, Double>();
	}
	
	public BeeNode(Position position) {
		adjacencyMap = new HashMap<BeeNode, Double>();
		this.position = position;
		this.obstructed = false;
	}
	
	public void addAdjacent(BeeNode node, Double cost) {
		adjacencyMap.put(node, cost);
	}
	
	public void removeAdjacent(BeeNode node) {
		adjacencyMap.remove(node);
	}
	
	public Position getPosition() {
		return position;
	}
	
	public Vector2D getVector2D() {
		return new Vector2D(position);
	}
	
	public void setObstructed(boolean obstructed) {
		this.obstructed = obstructed;
	}
	
	public boolean getObstructed() {
		return obstructed;
	}
	
	public void setEdgeCost(BeeNode node, Double cost) {
		adjacencyMap.replace(node, cost);
	}
	
	public double getEdgeCost(BeeNode node) {
		return adjacencyMap.get(node);
	}
	
	public void setTotalCost(double totalCost) {
		this.totalCost = totalCost;
	}
	
	public double getTotalCost() {
		return totalCost;
	}
	
	public HashMap<BeeNode, Double> getAdjacencyMap() {
		return adjacencyMap;
	}
	
	/*
	 * Returns the distance between this node and another node
	 * TODO: make this work with wrap around
	public double findDistance(BeeNode node) {
		return this.getVector2D().subtract(node.getVector2D()).getMagnitude();
	}
	*/
	
	@Override
	public int compareTo(BeeNode node) {
		return (int)(this.totalCost - node.getTotalCost());
	}
}
