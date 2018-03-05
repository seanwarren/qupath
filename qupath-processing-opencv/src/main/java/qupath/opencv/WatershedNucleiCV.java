/*-
 * #%L
 * This file is part of QuPath.
 * %%
 * Copyright (C) 2014 - 2016 The Queen's University of Belfast, Northern Ireland
 * Contact: IP Management (ipmanagement@qub.ac.uk)
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */

package qupath.opencv;


import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Rect;
import org.opencv.core.RotatedRect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.analysis.stats.RunningStatistics;
import qupath.lib.awt.common.AwtTools;
import qupath.lib.color.ColorDeconvMatrix3x3;
import qupath.lib.color.ColorDeconvolutionHelper;
import qupath.lib.color.ColorDeconvolutionStains;
import qupath.lib.color.StainVector;
import qupath.lib.common.GeneralTools;
import qupath.lib.geom.Point2;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.measurements.MeasurementList;
import qupath.lib.measurements.MeasurementListFactory;
import qupath.lib.objects.PathDetectionObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.helpers.PathObjectTools;
import qupath.lib.plugins.AbstractTileableDetectionPlugin;
import qupath.lib.plugins.ObjectDetector;
import qupath.lib.plugins.parameters.ParameterList;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.PolygonROI;
import qupath.lib.roi.RectangleROI;
import qupath.lib.roi.interfaces.ROI;
import qupath.opencv.processing.OpenCVTools;
import qupath.opencv.processing.ProcessingCV;

/**
 * Alternative (incomplete) attempt at nucleus segmentation.
 * 
 * It's reasonably fast... but not particularly good.
 * 
 * @author Pete Bankhead
 *
 */
public class WatershedNucleiCV extends AbstractTileableDetectionPlugin<BufferedImage> {
	
	private static Logger logger = LoggerFactory.getLogger(WatershedNucleiCV.class);
	
	transient private WatershedNuclei detector;
	
	
	class WatershedNuclei implements ObjectDetector<BufferedImage> {
	
		// TODO: REQUEST DOWNSAMPLE IN PLUGINS
		private List< PathObject> pathObjects = new ArrayList<>();
		
		
		@Override
		public Collection<PathObject> runDetection(final ImageData<BufferedImage> imageData, ParameterList params, ROI pathROI) {
			// Reset any detected objects
			pathObjects.clear();
			
			boolean splitShape = params.getBooleanParameterValue("splitShape");
//			double downsample = params.getIntParameterValue("downsampleFactor");
			
			double downsample = imageData.getServer().hasPixelSizeMicrons() ? 
					getPreferredPixelSizeMicrons(imageData, params) / imageData.getServer().getAveragedPixelSizeMicrons() :
						1;
			downsample = Math.max(downsample, 1);
			
			double threshold = params.getDoubleParameterValue("threshold");
			// Extract size-dependent parameters
			int medianRadius, openingRadius;
			double gaussianSigma, minArea;
			ImageServer<BufferedImage> server = imageData.getServer();
			if (server.hasPixelSizeMicrons()) {
				double pixelSize = 0.5 * downsample * (server.getPixelHeightMicrons() + server.getPixelWidthMicrons());
				medianRadius = (int)(params.getDoubleParameterValue("medianRadius") / pixelSize + .5);
				gaussianSigma = params.getDoubleParameterValue("gaussianSigma") / pixelSize;
				openingRadius = (int)(params.getDoubleParameterValue("openingRadius") / pixelSize + .5);
				minArea = params.getDoubleParameterValue("minArea") / (pixelSize * pixelSize);
				logger.trace(String.format("Sizes: %d, %.2f, %d, %.2f", medianRadius, gaussianSigma, openingRadius, minArea));
			} else {
				medianRadius = (int)(params.getDoubleParameterValue("medianRadius") + .5);
				gaussianSigma = params.getDoubleParameterValue("gaussianSigma");
				openingRadius = (int)(params.getDoubleParameterValue("openingRadius") + .5);
				minArea = params.getDoubleParameterValue("minArea");			
			}
			
			// TODO: Avoid hard-coding downsample
			Rectangle bounds = AwtTools.getBounds(pathROI);
			double x = bounds.getX();
			double y = bounds.getY();
			
	//		logger.info("BOUNDS: " + bounds);
			
			// Read the buffered image
			BufferedImage img = server.readBufferedImage(RegionRequest.createInstance(server.getPath(), downsample, pathROI));
			
			// Extract the color deconvolved channels
			// TODO: Support alternative stain vectors
			ColorDeconvolutionStains stains = imageData.getColorDeconvolutionStains();
			boolean isH_DAB = stains.isH_DAB();
			float[][] pxDeconvolved = colorDeconvolve(img, stains.getStain(1).getArray(), stains.getStain(2).getArray(), null, 2);
			float[] pxHematoxylin = pxDeconvolved[0];
			float[] pxDAB = isH_DAB ? pxDeconvolved[1] : null;
			
			// Convert to OpenCV Mat
			int width = img.getWidth();
			int height = img.getHeight();
			Mat mat = new Mat(height, width, CvType.CV_32FC1);
			
			// It seems OpenCV doesn't use the array directly, so no need to copy...
			mat.put(0, 0, pxHematoxylin);
	
			
			Mat matBackground = new Mat();
			
			Imgproc.medianBlur(mat, mat, 1);
			Imgproc.GaussianBlur(mat, mat, new Size(5, 5), 0.75);
			Imgproc.morphologyEx(mat, matBackground, Imgproc.MORPH_CLOSE, OpenCVTools.getCircularStructuringElement(1));
			ProcessingCV.morphologicalReconstruction(mat, matBackground);
			
			// Apply opening by reconstruction & subtraction to reduce background
			Imgproc.morphologyEx(mat, matBackground, Imgproc.MORPH_OPEN, OpenCVTools.getCircularStructuringElement(openingRadius));
			ProcessingCV.morphologicalReconstruction(matBackground, mat);
			Core.subtract(mat, matBackground, mat);
			
			// Apply Gaussian filter
			int gaussianWidth = (int)(Math.ceil(gaussianSigma * 3) * 2 + 1);
			Imgproc.GaussianBlur(mat, mat, new Size(gaussianWidth, gaussianWidth), gaussianSigma);
			
			// Apply Laplacian filter
			Mat matLoG = matBackground;
			Imgproc.Laplacian(mat, matLoG, mat.depth(), 1, -1, 0);
			
			// Threshold
			Mat matBinaryLoG = new Mat();
			Core.compare(matLoG, new Scalar(0), matBinaryLoG, Core.CMP_GT);
			
			// Watershed transform
			Mat matBinary = matBinaryLoG.clone();
			OpenCVTools.watershedIntensitySplit(matBinary, matLoG, 0, 1);
			
			// Identify all contours
			List<MatOfPoint> contours = new ArrayList<>();
			Imgproc.findContours(matBinary, contours, new Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
			
			// Create a labelled image for each contour
			Mat matLabels = new Mat(matBinary.size(), CvType.CV_32F, new Scalar(0));
			List<RunningStatistics> statsList = new ArrayList<>();
			List<MatOfPoint> tempContourList = new ArrayList<>(1);
			int label = 0;
			for (MatOfPoint contour : contours) {
				label++;
				tempContourList.clear();
				tempContourList.add(contour);
				Imgproc.drawContours(matLabels, tempContourList, 0, new Scalar(label), -1);
				statsList.add(new RunningStatistics());
			}
			// Compute mean for each contour, keep those that are sufficiently intense
			float[] labels = new float[(int)matLabels.total()];
			matLabels.get(0, 0, labels);
			computeRunningStatistics(pxHematoxylin, labels, statsList);
			int ind = 0;
			Scalar color = new Scalar(255);
			matBinary.setTo(new Scalar(0));
			for (RunningStatistics stats : statsList) {
				if (stats.getMean() > threshold) {
					tempContourList.clear();
					tempContourList.add(contours.get(ind));
					Imgproc.drawContours(matBinary, tempContourList, 0, color, -1);				
				}
				ind++;
			}
	
			// Dilate binary image & extract remaining contours
			Imgproc.dilate(matBinary, matBinary, Imgproc.getStructuringElement(Imgproc.CV_SHAPE_RECT, new Size(3, 3)));
			Core.min(matBinary, matBinaryLoG, matBinary);
			
			OpenCVTools.fillSmallHoles(matBinary, minArea*4);
			
			// Split using distance transform, if necessary
			if (splitShape)
				OpenCVTools.watershedDistanceTransformSplit(matBinary, openingRadius/4);
			
			// Create path objects from contours		
			contours = new ArrayList<>();
			Mat hierarchy = new Mat();
			Imgproc.findContours(matBinary, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
			ArrayList<Point2> points = new ArrayList<>();
			
			// Create label image
			matLabels.setTo(new Scalar(0));
			
			// Update the labels to correspond with the contours, and compute statistics
			label = 0;
			List<RunningStatistics> statsHematoxylinList = new ArrayList<>(contours.size());
			List<RunningStatistics> statsDABList = new ArrayList<>(contours.size());
			for (MatOfPoint contour : contours){
				
				// Discard single pixels / lines
				if (contour.size().height <= 2)
					continue;
				
				// Simplify the contour slightly
				MatOfPoint2f contour2f = new MatOfPoint2f();
				contour2f.fromArray(contour.toArray());
				MatOfPoint2f contourApprox = new MatOfPoint2f();
				Imgproc.approxPolyDP(contour2f, contourApprox, 0.5, true);
				contour2f = contourApprox;
				
				// Create a polygon ROI
		        points.clear();
		        for (org.opencv.core.Point p : contour2f.toArray())
		        	points.add(new Point2(p.x * downsample + x, p.y * downsample + y));
		        	        
		        // Add new polygon if it is contained within the ROI & measurable
		        PolygonROI pathPolygon = new PolygonROI(points);
		        if (!(pathPolygon.getArea() >= minArea)) {
		        	// Don't do a simpler < because we also want to discard the region if the area couldn't be measured (although this is unlikely)
		        	continue;
		        }
		        
	//	        logger.info("Area comparison: " + Imgproc.contourArea(contour) + ",\t" + (pathPolygon.getArea() / downsample / downsample));
	//	        Mat matSmall = new Mat();
		        if (pathROI instanceof RectangleROI || PathObjectTools.containsROI(pathROI, pathPolygon)) {
		        	MeasurementList measurementList = MeasurementListFactory.createMeasurementList(20, MeasurementList.TYPE.FLOAT);
		        	PathObject pathObject = new PathDetectionObject(pathPolygon, null, measurementList);
		        	
		        	measurementList.addMeasurement("Area", pathPolygon.getArea());
		        	measurementList.addMeasurement("Perimeter", pathPolygon.getPerimeter());
		        	measurementList.addMeasurement("Circularity", pathPolygon.getCircularity());
		        	measurementList.addMeasurement("Solidity", pathPolygon.getSolidity());
		        	
		        	// I am making an assumption regarding square pixels here...
		        	RotatedRect rrect = Imgproc.minAreaRect(contour2f);
		        	measurementList.addMeasurement("Min axis", Math.min(rrect.size.width, rrect.size.height) * downsample);
		        	measurementList.addMeasurement("Max axis", Math.max(rrect.size.width, rrect.size.height) * downsample);
		        		        	
		        	// Store the object
		        	pathObjects.add(pathObject);
		        	
		        	// Create a statistics object & paint a label in preparation for intensity stat computations later
		        	label++;
		        	statsHematoxylinList.add(new RunningStatistics());
		        	if (pxDAB != null)
		        		statsDABList.add(new RunningStatistics());
		        	tempContourList.clear();
		        	tempContourList.add(contour);
		        	Imgproc.drawContours(matLabels, tempContourList, 0, new Scalar(label), -1);
		        }
			}
			
			// Compute intensity statistics
			matLabels.get(0, 0, labels);
			computeRunningStatistics(pxHematoxylin, labels, statsHematoxylinList);
			if (pxDAB != null)
				computeRunningStatistics(pxDAB, labels, statsDABList);
			ind = 0;
			for (PathObject pathObject : pathObjects) {
				MeasurementList measurementList = pathObject.getMeasurementList();
				RunningStatistics statsHaem = statsHematoxylinList.get(ind);
				//    	pathObject.addMeasurement("Area (px)", statsHaem.nPixels() * downsample * downsample);
				measurementList.addMeasurement("Hematoxylin mean", statsHaem.getMean());
				measurementList.addMeasurement("Hematoxylin std dev", statsHaem.getStdDev());
				measurementList.addMeasurement("Hematoxylin min", statsHaem.getMin());
				measurementList.addMeasurement("Hematoxylin max", statsHaem.getMax());
				measurementList.addMeasurement("Hematoxylin range", statsHaem.getRange());
				
				if (pxDAB != null) {
					RunningStatistics statsDAB = statsDABList.get(ind);
					measurementList.addMeasurement("DAB mean", statsDAB.getMean());
					measurementList.addMeasurement("DAB std dev", statsDAB.getStdDev());
					measurementList.addMeasurement("DAB min", statsDAB.getMin());
					measurementList.addMeasurement("DAB max", statsDAB.getMax());
					measurementList.addMeasurement("DAB range", statsDAB.getRange());
				}
				
				measurementList.closeList();
				ind++;
			}
			logger.info("Found " + pathObjects.size() + " contours");
	
			return pathObjects;
		}
		
		
		
		
		@Override
		public String getLastResultsDescription() {
			return String.format("Detected %d nuclei", pathObjects.size());
		}

		
	}
	
	

	@Override
	public ParameterList getDefaultParameterList(final ImageData<BufferedImage> imageData) {
		ParameterList params = new ParameterList(getName());
		params.addDoubleParameter("preferredMicrons", "Preferred pixel size", 0.5, GeneralTools.micrometerSymbol());
//				addIntParameter("downsampleFactor", "Downsample factor", 2, "", 1, 4);
		
		if (imageData.getServer().hasPixelSizeMicrons()) {
			String um = GeneralTools.micrometerSymbol();
			params.addDoubleParameter("medianRadius", "Median radius", 1, um).
				addDoubleParameter("gaussianSigma", "Gaussian sigma", 1.5, um).
				addDoubleParameter("openingRadius", "Opening radius", 8, um).
				addDoubleParameter("threshold", "Threshold", 0.1, null, 0, 1.0).
				addDoubleParameter("minArea", "Minimum area", 25, um+"^2");
		} else {
			params.setHiddenParameters(true, "preferredMicrons");
			params.addDoubleParameter("medianRadius", "Median radius", 1, "px").
					addDoubleParameter("gaussianSigma", "Gaussian sigma", 2, "px").
					addDoubleParameter("openingRadius", "Opening radius", 20, "px").
					addDoubleParameter("threshold", "Threshold", 0.1, null, 0, 1.0).
					addDoubleParameter("minArea", "Minimum area", 100, "px^2");
		}
		params.addBooleanParameter("splitShape", "Split by shape", true);			
		return params;
	}

	@Override
	public String getName() {
		return "OpenCV nucleus experiment";
	}

	public RunningStatistics computeRunningStatistics(float[] pxIntensities, byte[] pxMask, int width, Rect bounds) {
		RunningStatistics stats = new RunningStatistics();
		for (int i = 0; i < pxMask.length; i++) {
			if (pxMask[i] == 0)
				continue;
			// Compute the image index
			int x = i % bounds.width + bounds.x;
			int y = i % bounds.width + bounds.y;
			// Add the value
			stats.addValue(pxIntensities[y * width + x]);
		}
		return stats;
	}
	
	
	
	@Override
	public String getLastResultsDescription() {
		return detector == null ? "" : detector.getLastResultsDescription();
	}
	
	@Override
	public String getDescription() {
		return "Alternative nucleus detection";
	}
	
	
	
	// TODO: If this ever becomes important, switch to using the QuPathCore implementation instead of this one
	@Deprecated
	public static float[][] colorDeconvolve(BufferedImage img, double[] stain1, double[] stain2, double[] stain3, int nStains) {
		// TODO: Precompute the default matrix inversion
		if (stain3 == null)
			stain3 = StainVector.cross3(stain1, stain2);
		double[][] stainMat = new double[][]{stain1, stain2, stain3};
		ColorDeconvMatrix3x3 mat3x3 = new ColorDeconvMatrix3x3(stainMat);
		double[][] matInv = mat3x3.inverse();
		double[] stain1Inv = matInv[0];
		double[] stain2Inv = matInv[1];
		double[] stain3Inv = matInv[2];
	
		// Extract the buffered image pixels
		int[] buf = img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
		// Preallocate the output
		float[][] output = new float[nStains][buf.length];
		
		// Apply color deconvolution
		double[] od_lut = ColorDeconvolutionHelper.makeODLUT(255, 256);
		for (int i = 0; i < buf.length; i++) {
			int c = buf[i];
			// Extract RGB values & convert to optical densities using a lookup table
			double r = od_lut[(c & 0xff0000) >> 16];
			double g = od_lut[(c & 0xff00) >> 8];
			double b = od_lut[c & 0xff];
			// Apply deconvolution & store the results
			for (int s = 0; s < nStains; s++) {
				output[s][i] = (float)(r * stain1Inv[s] + g * stain2Inv[s] + b * stain3Inv[s]);
			}
		}
		return output;
	}

	@Override
	protected double getPreferredPixelSizeMicrons(ImageData<BufferedImage> imageData, ParameterList params) {
		if (imageData.getServer().hasPixelSizeMicrons())
			return Math.max(params.getDoubleParameterValue("preferredMicrons"), imageData.getServer().getAveragedPixelSizeMicrons());
		return 0.5;
	}

	@Override
	protected ObjectDetector<BufferedImage> createDetector(ImageData<BufferedImage> imageData, ParameterList params) {
		return new WatershedNuclei();
	}

	@Override
	protected int getTileOverlap(ImageData<BufferedImage> imageData, ParameterList params) {
		return 50;
	}
	
	
	
	private static void computeRunningStatistics(float[] pxIntensities, float[] pxLabels, List<RunningStatistics> statsList) {
		float lastLabel = Float.NaN;
		int nLabels = statsList.size();
		RunningStatistics stats = null;
		for (int i = 0; i < pxIntensities.length; i++) {
			float label = pxLabels[i];
			if (label == 0 || label > nLabels)
				continue;
			// Get a new statistics object if necessary
			if (label != lastLabel) {
				stats = statsList.get((int)label-1);
				lastLabel = label;
			}
			// Add the value
			stats.addValue(pxIntensities[i]);
		}
	}
	
}