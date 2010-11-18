/*
 * Alternate way of computing the bounding rectangle in doBackground()

		try {writeJpg(biw, -1f, new FileOutputStream("C:\\aa0.jpg"));}
		catch (Exception e) {}
		
	    
	    // apply the 4 filters for computing the edges
		GreyscaleFilter s1 = new GreyscaleFilter();
	    biw = s1.filter(biw);

        try {writeJpg(biw, -1.00f, new FileOutputStream("C:\\aa1.jpg"));}
		catch (Exception e) {}
		
		SobelEdgeDetectorFilter s2 = new SobelEdgeDetectorFilter();
		biw = s2.filter(biw, null, true);

		try {writeJpg(biw, -1.00f, new FileOutputStream("C:\\aa2.jpg"));}
		catch (Exception e) {}

	    int bgGray = GreyscaleFilter.calculateGrey(
	    		bgColor.getRed(), bgColor.getGreen(), bgColor.getBlue(), 
				s1.getGreyscaleType());
	    int foreGray = 255 - bgGray;
	    
	    ThresholdFilter s3 = new ThresholdFilter();
	    s3.setThresholdLimit(bgGray);
	    biw = s3.filter(biw);
	    
	    LineHoughTransformOp s4 = new LineHoughTransformOp();
        s4.setLocalPeakNeighbourhood(7); // 0 .. 20
        s4.run(biw);
        ArrayList edges = s4.getEdges(biw, 0.25d); // 0.00d .. 1.00 d
	    
        int[] edge;
        for (int i = 0; i < edges.size(); i++) {
        	edge = (int[])edges.get(i);
        	edges.set(i, new GeomEdge(edge[0] + x1, edge[1] + y1, 
        			edge[2] + x1, edge[3] + y1));
		}
 */

package com.alexalecu.imageUtil;

import java.awt.Color;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;
import java.util.concurrent.ExecutionException;

import javax.imageio.ImageIO;
import javax.swing.SwingWorker;

public class AutoSelectTask extends SwingWorker<Object[], AutoSelectStatus> {
	public final static int MIN_ADJACENT_PIXELS_FOR_CROP = 5;
	
	// disable using a disk-based cache file to speed working with images
	static {
		ImageIO.setUseCache(false);
	}
	
	private AutoSelectStatus autoSelectStatus; // the current status of the task
	private Object[] result; // the result of the task execution
	
	private BufferedImage image;
	private Rectangle selectionRect;
	private Color bgColor;
	private int bgTolerance;
	private Color fgColor;
	private ImageCropMethod cropMethod;


	/**
	 * @return the current status of the task
	 */
	public AutoSelectStatus getAutoSelectStatus()
	{
		return autoSelectStatus;
	}

	/**
	 * set the current status of the task and trigger a property change event
	 * @param autoSelectStatus
	 */
	public void setAutoSelectStatus(AutoSelectStatus autoSelectStatus)
	{
		AutoSelectStatus old = this.autoSelectStatus;
		this.autoSelectStatus = autoSelectStatus;
		getPropertyChangeSupport().firePropertyChange("autoSelectStatus", old, autoSelectStatus);
	}

	/**
	 * @return the result of the task execution
	 */
	public Object[] getResult()
	{
		return result;
	}

	/**
	 * set the result of the task execution and trigger a property change event
	 * @param result
	 */
	public void setResult(Object[] result)
	{
		Object[] oldResult = this.result;
		this.result = result;
		getPropertyChangeSupport().firePropertyChange("result", oldResult, result);
	}

	/**
	 * set the BufferedImage to work on
	 * @param image
	 */
	public void setImage(BufferedImage image) {
		if (getState() == StateValue.PENDING || getState() == StateValue.DONE)
			this.image = image;
	}

	/**
	 * set the selection rectangle to start from
	 * @param selectionRect
	 */
	public void setSelectionRect(Rectangle selectionRect) {
		if (getState() == StateValue.PENDING || getState() == StateValue.DONE)
			this.selectionRect = selectionRect;
	}

	/**
	 * set the background color to look for
	 * @param bgColor
	 */
	public void setBgColor(Color bgColor) {
		if (getState() == StateValue.PENDING || getState() == StateValue.DONE) {
			this.bgColor = bgColor;
			fgColor = new Color(255 - bgColor.getRed(), 255 - bgColor.getGreen(),
					255 - bgColor.getBlue());
		}
	}

	/**
	 * set the background tolerance to take into account when matching the background color
	 * @param bgTolerance
	 */
	public void setBgTolerance(int bgTolerance) {
		if (getState() == StateValue.PENDING || getState() == StateValue.DONE)
			this.bgTolerance = (int)(255 * bgTolerance / 100);
	}

	/**
	 * set the crop method to use, minimum or maximum
	 * @param cropMethod
	 */
	public void setCropMethod(ImageCropMethod cropMethod) {
		if (getState() == StateValue.PENDING || getState() == StateValue.DONE)
			this.cropMethod = cropMethod;
	}

	/**
	 * compute the rectangle which is the optimized solution for cropping the source BufferedImage
	 * @return an array containing two Objects; the first one is the resulting Rectangle,
	 * while the 2nd object is an ArrayList containing the polygon edges
	 */
	@Override
	protected Object[] doInBackground() {
		// compute the coordinates of the minimum rectangle which accommodates the whole image
		publish(AutoSelectStatus.SelectBoundingRectangle);
		Rectangle maxRect = getMinBoundingRectangle();
		if (maxRect == null || isCancelled()) // return if the task has been cancelled
			return new Object[] {null, null};
		
		// cut just the section that concerns me
		BufferedImage biw = ImageConvert.cropImageNew(image, maxRect);
		if (isCancelled()) // return if the task has been cancelled
			return new Object[] {null, null};
		
		Rectangle imageBoundRect = new Rectangle(0, 0, biw.getWidth(), biw.getHeight());
		
		// convert the image to 2 color only:
		// the background area to background color
		// the rest to the color opposite to the background one
		publish(AutoSelectStatus.ReduceImageColors);
		reduceColors(biw, imageBoundRect);
		if (isCancelled()) // return if the task has been cancelled
			return new Object[] {null, null};
		
		ConvexHull polygon = new ConvexHull();
        Rectangle polygonRect;

		// scan the image to find the hull envelope points
		publish(AutoSelectStatus.FindEdgePoints);
		List<GeomPoint> points = getEnvelopePoints(biw, imageBoundRect, 0);
		if (points == null || isCancelled()) // return if the task has been cancelled
			return new Object[] {null, null};
		
		// compute the polygon vertices and shift their coordinates
		publish(AutoSelectStatus.FindVertices);
		List<GeomPoint> vertices = getVertices(biw, points);
		if (vertices == null || isCancelled()) // return if the task has been cancelled
			return new Object[] {null, null};
        for (int i = 0; i < vertices.size(); i++) {
        	GeomPoint p = vertices.get(i);
        	p.setX(p.getX() + maxRect.x);
        	p.setY(p.getY() + maxRect.y);
        	polygon.addPoint(p);
        }

		// if -1 or if >= the width or height of the maximum rectangle,
		// then the max rectangle is computed, otherwise the min one
		int nrMatches = cropMethod == ImageCropMethod.CropMinimum
				? MIN_ADJACENT_PIXELS_FOR_CROP : -1;
		
		// if the minimum rectangle calculation is desired...
		if (nrMatches > -1 && maxRect.width > nrMatches && maxRect.height > nrMatches) {
			publish(AutoSelectStatus.ComputeLargestRectangle);
			polygon.computeLargestRectangle();
			polygonRect = new Rectangle(polygon.rectp.getX(), polygon.rectp.getY(),
					polygon.rectw, polygon.recth);
		}
		else {
			publish(AutoSelectStatus.ComputeEdgeList);
			polygonRect = new Rectangle(maxRect.x, maxRect.y, maxRect.width, maxRect.height);
			polygon.computeEdgeList();
		}
		
		publish(AutoSelectStatus.Finished);
		return new Object[] {polygonRect, polygon.edgeList};
	}

	@Override
	protected void process(List<AutoSelectStatus> statusList) {
		setAutoSelectStatus(statusList.get(statusList.size() - 1));
	}

	@Override
	public void done() {
		try {
			setResult(get());
		}
		catch (InterruptedException ignore) {
			setResult(new Object[] {null, null});
		}
		catch (ExecutionException e) {
			setResult(new Object[] {null, null});
		}
	}

	
	/**
	 * compute the coordinates of the minimum rectangle which accommodates the whole image
	 * @return the minimum rectangle which contains the whole image
	 */
	private Rectangle getMinBoundingRectangle() {
		// initialize some local variables
		int left = selectionRect.x;
		int right = selectionRect.x + selectionRect.width - 1;
		int top = selectionRect.y;
		int bottom = selectionRect.y + selectionRect.height - 1;
		
		boolean loopL = true, loopR = true, loopT = true, loopB = true;
		byte directionL = 0, directionR = 0, directionT = 0, directionB = 0;
		int prevL, prevR, prevT, prevB;
		
		// keep processing till no edge can be moved any more
		while (loopL || loopR || loopT || loopB) {
			prevL = left;
			while (loopL) {
				if (ImageColors.isBgColor(image, left, true, top, bottom, bgColor, bgTolerance)) {
					// stop if the previous move was backwards or not enough room
					// and move the left forward only if the right is far enough
					if (directionL != -1 && left < right - 1) {
						directionL = 1;
						left++;
					}
					else {
						if (left < right - 1)
							left++;
						loopL = false;
					}
				}
				else {
					// if the left has not moved forward during this step and
					// we're on non-bg color, move it backwards and scan again
					if (directionL != 1 && left > 0) {
						directionL = -1;
						left--;
					}
					else {
						loopL = false;
					}
				}
				if (isCancelled()) // check if the task has been cancelled
					return null;
			}

			prevR = right;
			while (loopR) {
				if (ImageColors.isBgColor(image, right, true, top, bottom, bgColor, bgTolerance)) {
					if (directionR != 1 && left < right - 1) {
						directionR = -1;
						right--;
					}
					else {
						if (left < right - 1)
							right--;
						else if (right < image.getWidth() - 1)
							right++;
						loopR = false;
					}
				}
				else {
					if (directionR != -1 && right < image.getWidth() - 1) {
						directionR = 1;
						right++;
					}
					else {
						loopR = false;
					}
				}
				if (isCancelled()) // check if the task has been cancelled
					return null;
			}

			// if the left or right edge have changed, make sure we process
			// the top and bottom too
			if (prevL != left || prevR != right) {
				if (!loopT) {
					directionT = 0;
					loopT = true;
				}
				if (!loopB) {
					directionB = 0;
					loopB = true;
				}
			}
			
			prevT = top;
			while (loopT) {
				if (ImageColors.isBgColor(image, top, false, left, right, bgColor, bgTolerance)) {
					if (directionT != -1 && top < bottom - 1) {
						directionT = 1;
						top++;
					}
					else {
						if (top < bottom - 1)
							top++;
						loopT = false;
					}
				}
				else {
					if (directionT != 1 && top > 0) {
						directionT = -1;
						top--;
					}
					else {
						loopT = false;
					}
				}
				if (isCancelled()) // check if the task has been cancelled
					return null;
			}
			
			prevB = bottom;
			while (loopB) {
				if (ImageColors.isBgColor(image, bottom, false, left, right, bgColor,bgTolerance)) {
					if (directionB != 1 && top < bottom - 1) {
						directionB = -1;
						bottom--;
					}
					else {
						if (top < bottom - 1)
							bottom--;
						else if (bottom < image.getHeight() - 1)
							bottom++;
						loopB = false;
					}
				}
				else {
					if (directionB != -1 && bottom < image.getHeight() - 1) {
						directionB = 1;
						bottom++;
					}
					else {
						loopB = false;
					}
				}
				if (isCancelled()) // check if the task has been cancelled
					return null;
			}

			// if the top or bottom edge have changed, make sure we process
			// the left and right too
			if (prevT != top || prevB != bottom) {
				if (!loopL) {
					directionL = 0;
					loopL = true;
				}
				if (!loopR) {
					directionR = 0;
					loopR = true;
				}
			}
		}
		
		return new Rectangle(left, top, right - left + 1, bottom - top + 1);
	}
	
	/**
	 * all pixels which do not match the bg color are converted to the fg color;
	 * the conversion is applied only within the bounding rectangle 
	 * @param bi the BufferedImage to be converted
	 * @param boundingRect the bounding rectangle where the conversion is applied
	 */
	public void reduceColors(BufferedImage bi, Rectangle boundingRect) {
		// define some easier to use variables
		int startX = boundingRect.x;
		int startY = boundingRect.y;
		int endX = boundingRect.x + boundingRect.width - 1;
		int endY = boundingRect.y + boundingRect.height - 1;
		
		// scan the image on the vertical, from the left edge of the bounding rectangle to the right
		// edge of it, looking for pixels not matching the bg color and converting them to fg color
		for (int j = startY; j <= endY; j++) {
			
			// get the start and the end coordinates on the current horizontal
			// line where the non-background color zone is located
			int res[] = ImageColors.getColorMargins(bi, j, false, startX, endX,
					bgColor, bgTolerance);

			if (isCancelled()) // check if the task has been cancelled
				return;
			
			// if no coordinates have been found, the whole line is bg
			// color; convert it to bg color
			if (res[0] == -1 && res[1] == -1) {
				for (int i = startX; i <= endX; i++)
					bi.setRGB(i, j, bgColor.getRGB());
			}
			else {
				// both res[0] and res[1] are > -1 in this case
				// the first and last sections are bg, the middle one is fg 
				for (int i = startX; i < res[0]; i++)
					bi.setRGB(i, j, bgColor.getRGB());
				for (int i = res[0]; i <= res[1]; i++)
					bi.setRGB(i, j, fgColor.getRGB());
				for (int i = res[1] + 1; i <= endX; i++)
					bi.setRGB(i, j, bgColor.getRGB());
			}

			if (isCancelled()) // check if the task has been cancelled
				return;
		}
	}
	
	/**
	 * Return a list containing all the points located on the hull envelope,
	 * starting with the top left one and going counter-clockwise on the hull
	 * @param bi the BufferedImage to scan
	 * @param boundingRect the bounding rectangle containing the area to scan
	 * @param bgTol the tolerance used when trying to match the background color
	 * @return an ArrayList of GeomPoint objects representing the hull vertices
	 */
	public List<GeomPoint> getEnvelopePoints(BufferedImage bi, Rectangle boundingRect, int bgTol) {
		// set up some helper properties
		int startX = boundingRect.x;
		int startY = boundingRect.y;
		int endX = boundingRect.x + boundingRect.width - 1;
		int endY = boundingRect.y + boundingRect.height - 1;
		
		// the list containing the points on the left side of the hull
		Stack<GeomPoint> pointsL = new Stack<GeomPoint>();
		// the list containing the points on the top, right and bottom sides
		Stack<GeomPoint> pointsR = new Stack<GeomPoint>();
		
		int[] marginsPrev = null;
		boolean breakOut = false;
		
		for (int y = startY; y <= endY + 1; y++) {
			// find the limits of the non-bg color
			int[] margins = ImageColors.getColorMargins(bi, y, false, startX, endX, bgColor, bgTol);
			
			// if no limits were found, the whole line is bg color
			if (margins[0] == -1 || margins[1] == -1) {
				// continue scanning if lines containing non-bg color were not
				// found already
				if (!breakOut)
					continue;
				
				// the last found line (which contains non-bg color) represents
				// the bottom edge of the hull, so let's add the points to the
				// envelope point list, taking into account the direction
				if (!pointsR.empty()) {
					pointsR.pop();
					for (int x = marginsPrev[0] + 1; x <= marginsPrev[1]; x++)
						pointsL.push(new GeomPoint(x, y - 1));
				}
				
				// exit the loop as the hull has been scanned successfully
				break;
			}
			
			// if it's the first line containing non-bg color found, then it is
			// the top edge of the hull, so add all its points to the list
			if (!breakOut) {
				breakOut = true;
				pointsL.push(new GeomPoint(margins[0], y));
				for (int x = margins[0] + 1; x <= margins[1]; x++)
					pointsR.push(new GeomPoint(x, y));
			}
			// otherwise add the leftmost pixel to the left list, the rightmost
			// pixel to the right list
			else {
				pointsL.push(new GeomPoint(margins[0], y));
				pointsR.push(new GeomPoint(margins[1], y));
			}
			
			marginsPrev = margins;

			if (isCancelled()) // check if the task has been cancelled
				return null;
		}
		
		// merge the lists
		while (pointsR.size() > 0)
			pointsL.push(pointsR.pop());
		
		return pointsL;
	}
	
	/**
	 * Scan the hull located on the image and find the hull vertices
	 * @param bi the BufferedImage containing the hull of color != bgColor
	 * @param points the list of points on the hull edges
	 * @return a list of GeomPoint objects representing the hull vertices
	 */
	public List<GeomPoint> getVertices(BufferedImage bi, List<GeomPoint> points) {
		Stack<GeomPoint> vertices = new Stack<GeomPoint>();
		
		if (points.isEmpty())
			return vertices;
		
		// convert to a collection more suitable for our purposes
		points = new ArrayList<GeomPoint>(points);
		
		GeomPoint pOrig = points.get(0);
		int offsetX = pOrig.getX();
		int offsetY = pOrig.getY();
		
		// shift the axis origin to the top left point, update all coordinates
		for (int i = 0; i < points.size(); i++) {
			GeomPoint p = points.get(i);
			p.setX(p.getX() - offsetX);
			p.setY(p.getY() - offsetY);
		}
		
		// scan the envelope point list and remove those located on the same
		// edge
		double tanPrv = 0d;
		double tanCrt;
		vertices.push(pOrig);
		for (int i = 1; i < points.size(); i++) {
			GeomPoint p = points.get(i);
			tanCrt = ((double)p.getY() - vertices.peek().getY()) /
					(p.getX() - vertices.peek().getX());
			
			// if the previous tangent is not the same as the current one, we
			// are on a new edge, so lets update the variables
			if (tanPrv != tanCrt || i == 1) {
				tanPrv = tanCrt;
				vertices.add(p);
			}
			else {
				// same edge here, update the last vertex to the current point
				vertices.pop();
				vertices.push(p);
			}
			
			if (isCancelled()) // check if the task has been cancelled
				return null;
		}
		
		// shift the axis origin back, update the vertex coordinates
		for (int i = 0; i < vertices.size(); i++) {
			GeomPoint p = vertices.get(i);
			p.setX(p.getX() + offsetX);
			p.setY(p.getY() + offsetY);
		}
		
		return vertices;
	}

}
