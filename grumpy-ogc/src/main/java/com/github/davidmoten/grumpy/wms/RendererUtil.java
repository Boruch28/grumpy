package com.github.davidmoten.grumpy.wms;

import static java.util.Arrays.asList;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import com.github.davidmoten.grumpy.core.Position;
import com.github.davidmoten.grumpy.projection.FeatureUtil;
import com.github.davidmoten.grumpy.projection.Projector;
import com.github.davidmoten.grumpy.projection.ProjectorBounds;
import com.github.davidmoten.grumpy.util.NearBSpline;

public class RendererUtil {

    private static final double DEFAULT_MAX_DISTANCE_BETWEEN_POINTS_IN_PIXELS = 20;
    private static final double MAX_DISTANCE_BETWEEN_POINTS_IN_DEGREES = 300;

    public static void useAntialiasing(Graphics2D g) {
        RenderingHints renderHints = new RenderingHints(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        renderHints.put(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        renderHints.put(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.addRenderingHints(renderHints);
    }

    /**
     * returns the elements of a great circle path between a and b that
     * correspond to the individual pixels along the path
     * 
     * @param projector
     * @param a
     * @param b
     * @return
     */
    public static List<Position> joinPixels(Projector projector, Position a, Position b,
            double maxDistanceBetweenPointsInPixels) {
        if (maxDistanceBetweenPointsInPixels < 2)
            throw new RuntimeException("distance between points in pixels must be greater than 1");
        List<Position> list = new ArrayList<Position>();
        Point pointA = projector.toPoint(a.getLat(), a.getLon());
        Point pointB = projector.toPoint(b.getLat(), b.getLon());
        // check for distance between in km because projection
        // issues at the 180, -180 boundary
        double maxDistanceBetweenPointsInKm = 20;
        if (a.getDistanceToKm(b) > maxDistanceBetweenPointsInKm
                && pointA.distance(pointB) >= maxDistanceBetweenPointsInPixels) {
            Position intermediate = a.getPositionAlongPath(b, 0.5);
            // use recursion, O(log(n)) stack calls because of the division by 2
            // above
            List<Position> segment1 = joinPixels(projector, a, intermediate, maxDistanceBetweenPointsInPixels);
            List<Position> segment2 = joinPixels(projector, intermediate, b, maxDistanceBetweenPointsInPixels);
            list.addAll(segment1);
            // remove the start of segment 2 because it is the finish of segment
            // 1
            segment2.remove(0);
            list.addAll(segment2);
        } else {
            list.add(a);
            list.add(b);
        }
        return list;
    }

    public static List<Position> joinPixels(Projector projector, double maxDistanceBetweenPointsInPixels,
            List<Position> mainPositions) {
        List<Position> positions = new ArrayList<Position>();
        {
            Position lastPosition = null;
            for (Position position : mainPositions) {
                if (lastPosition != null) {
                    List<Position> list = joinPixels(projector, lastPosition, position,
                            maxDistanceBetweenPointsInPixels);
                    if (positions.size() > 0)
                        list.remove(0);
                    positions.addAll(list);
                }
                lastPosition = position;
            }
        }
        return positions;
    }

    public static Shape getPath(Projector projector, List<Position> positions) {
        GeneralPath path = new GeneralPath();
        if (positions.size() < 2)
            throw new RuntimeException("must provide at least two positions");

        addToPath(projector, positions, path, 0);
        addToPath(projector, positions, path, projector.periodAtLat(0));

        return path;
    }

    private static void addToPath(Projector projector, List<Position> positions, GeneralPath path, double deltaX) {
        Position firstPosition = positions.get(0);
        com.vividsolutions.jts.geom.Point first = projector.getGeometryPointInSrs(firstPosition.getLat(),
                firstPosition.getLon());// projected values
        Double currentLat = null;
        Double currentLon = null;
        com.vividsolutions.jts.geom.Point currentPoint = null;
        for (Position p : positions) {
            if (currentPoint == null) {
                currentPoint = projector.getFirstXAfter(projector, p.getLat(), p.getLon(), projector.getBounds()
                        .getMinX() - deltaX);
                currentLat = p.getLat();
                currentLon = p.getLon();
                Point2D.Double pt = projector.getTargetPoint(currentPoint);
                path.moveTo(pt.x, pt.y);
            } else {
                com.vividsolutions.jts.geom.Point point = projector.getGeometryPointInSrsRelativeTo(p.getLat(),
                        p.getLon(), currentLat, currentLon, currentPoint.getX(), currentPoint.getY());
                Point2D.Double pt = projector.getTargetPoint(point);
                path.lineTo(pt.x, pt.y);
            }
            // GeneralPath line = new NearBSpline(getPoints(projector,
            // positions)).getPath();
            //
            // path.append(line.getPathIterator(AffineTransform.getTranslateInstance(0,
            // 0)), true);

        }
    }

    public static Shape getPath2(Projector projector, List<Position> positions) {
        return getPath(projector, DEFAULT_MAX_DISTANCE_BETWEEN_POINTS_IN_PIXELS, positions);
    }

    public static Shape getPath(Projector projector, double maxDistanceBetweenPointsInPixels,
            List<Position> mainPositions) {

        if (mainPositions.size() < 2)
            throw new RuntimeException("must provide at least two positions");

        GeneralPath path = new GeneralPath();
        Position lastPosition = null;
        for (Position position : mainPositions) {

            // normalize position within -180 and 180 degrees
            if (position.getLon() > 180) {
                position = new Position(position.getLat(), position.getLon() - 360);
            } else if (position.getLon() < -180) {
                position = new Position(position.getLat(), position.getLon() + 360);
            }

            if (lastPosition != null) {
                double currentLon = position.getLon();
                double currentLat = position.getLat();
                double lastLon = lastPosition.getLon();

                // avoid the 180 degree longitude boundary, don't try to draw
                // lines across it.
                // TODO fix this!
                if (!differenceIsLarge(lastLon, currentLon, MAX_DISTANCE_BETWEEN_POINTS_IN_DEGREES)) {
                    List<Position> positions = joinPixels(projector, maxDistanceBetweenPointsInPixels,
                            asList(lastPosition, position));
                    GeneralPath line = new NearBSpline(getPoints(projector, positions)).getPath();

                    path.append(line.getPathIterator(AffineTransform.getTranslateInstance(0, 0)), true);
                } else {
                    // move to the next point
                    Point p = projector.toPoint(currentLat, currentLon);
                    path.moveTo(p.x, p.y);
                }
            }
            lastPosition = position;
        }

        return path;
    }

    protected static boolean differenceIsLarge(double lastLon, double lon, double largeDifference) {
        return Math.abs(lastLon - lon) > largeDifference;
    }

    public static Point2D[] getPoints(Projector projector, List<Position> positions) {
        List<Point2D> points = new ArrayList<Point2D>();
        for (Position position : positions)
            points.add(projector.toPoint2D(position.getLat(), position.getLon()));
        return points.toArray(new Point2D[] {});
    }

    public static List<Position> getCircleOld(Position position, double radiusKm, double numPoints) {

        List<Position> positions = new ArrayList<Position>();
        for (int i = 0; i < numPoints; i++) {
            double bearing = 360.0 * i / numPoints;
            Position p = position.predict(radiusKm, bearing).normalizeLongitude();
            p = p.ensureContinuous(position.normalizeLongitude());
            positions.add(p);
        }
        positions.add(positions.get(0));
        return positions;
    }

    public static List<Position> getCircle(Position position, double radiusKm, double numPoints) {

        List<Position> positions = new ArrayList<Position>();
        for (int i = 0; i < numPoints; i++) {
            double bearing = 360.0 * i / numPoints;
            Position p = position.predict(radiusKm, bearing).normalizeLongitude();
            positions.add(p);
        }
        positions.add(positions.get(0));
        return positions;
    }

    public static WmsRequest getAustralianEpsg4326WmsRequest(int width, int height) {
        List<String> e = Collections.emptyList();
        WmsRequest r = new WmsRequest(e, e, e, new ProjectorBounds(FeatureUtil.EPSG_4326, 90, -61, 176, 61),
                "image/png", width, height, true, Color.white, "1.1.1", null, new HashMap<String, String>(), null, null);

        return r;
    }

}
