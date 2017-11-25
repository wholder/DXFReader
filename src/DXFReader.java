import javax.swing.*;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;
import java.util.List;

  /*
   *  This code implements a simple DXF file parser that can read many 2D DXF files containing POLYLINE and SPLINE
   *  outlines such as thoes used for embroidery patterns and input to machines like Silhouette paper cutters.
   *  It's designed to convert POLYLINE and SPLINE sequences into an array of Path2D.Double objects from Java's
   *  geom package.  The parser assumes that DXF file's units are inches, but you can pass the parser a maximum
   *  size value and it will scale down the converted shape so that its maximum dimension fits within this limit.
   *  The code also contains a simple viewer app you can run to try it out on a DXF file.  From the command line
   *  type:
   *          java -jar DXFReader.jar file.dxf
   *
   *  I've tested this code with a variety of simple, 2D DXF files and it's able to read most of them.  However,
   *  the DXF file specification is very complex and I have only implemented a subset of it, so I cannot guarantee
   *  that this code will read all 2D DXF files.
   *
   *  I'm publishing this source code under the MIT License (See: https://opensource.org/licenses/MIT)
   *
   *  Copyright 2017 Wayne Holder
   *
   *  Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
   *  documentation files (the "Software"), to deal in the Software without restriction, including without limitation
   *  the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and
   *  to permit persons to whom the Software is furnished to do so, subject to the following conditions:
   *
   *  The above copyright notice and this permission notice shall be included in all copies or substantial portions of
   *  the Software.
   *
   *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO
   *  THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
   *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,
   *  TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
   */

public class DXFReader {
  private ArrayList<Entity>   stack = new ArrayList<>();
  private Map<String,String>  hVariables;                 // Map of Header Variables
  private Entity              cEntity = null;

  static class Entity {
    private String        type;

    Entity (String type) {
      this.type = type;
    }

    void setType (String type) {
      if (this.type == null) {
        this.type = type;
      }
    }

    // Override these methods is subclasses, as needed
    void addParm (int gCode, String value) { }

    void addChild (Entity child) { }

    void close () { }
  }

  static class Header extends Entity {
    private Map<String,String>  variables = new TreeMap<>();
    private String              vName;

    Header (String type) {
      super(type);
    }

    @Override
    void addParm (int gCode, String value) {
      if (vName != null) {
        variables.put(vName, value);
        vName = null;
      }
      if (gCode == 9) {
        vName = value;
      }
    }
  }

  static class Polyline extends Entity {
    Path2D.Double     path = new Path2D.Double();
    List<Vertex>      points;
    private boolean   firstPoint = true;
    private boolean   closePath;

    Polyline (String type) {
      super(type);
    }

    @Override
    void addParm (int gCode, String value) {
      if (gCode == 70) {
        int flags = Integer.parseInt(value);
        closePath = (flags & 1) != 0;
      }
    }

    @Override
    void addChild (Entity child) {
      if (child instanceof Vertex) {
        if (points == null) {
          points = new ArrayList<>();
        }
        points.add((Vertex) child);
      }
    }

    @Override
    void close () {
      for (Vertex vertex : points) {
        if (firstPoint) {
          firstPoint = false;
          path.moveTo(vertex.xx, vertex.yy);
        } else {
          path.lineTo(vertex.xx, vertex.yy);
        }
      }
      if (closePath) {
        path.closePath();
      }
    }
  }

  static class Spline extends Entity {
    Path2D.Double         path = new Path2D.Double();
    List<Point2D.Double>  cPoints = new ArrayList<>();
    private double        xCp, yCp;
    private boolean       hasXcp, hasYcp;
    private boolean       closed;
    private int           numCPs;

    Spline (String type) {
      super(type);
    }

    @Override
    void addParm (int gCode, String value) {
      switch (gCode) {
      case 10:                              // Control Point X
        xCp = Double.parseDouble(value);
        hasXcp = true;
        break;
      case 20:                              // Control Point Y
        yCp = Double.parseDouble(value);
        hasYcp = true;
        break;
      case 70:
        int flags = Integer.parseInt(value);
        closed = (flags & 0x01) != 0;
        break;
      case 73:
        numCPs = Integer.parseInt(value);
        break;
      }
      if (hasXcp && hasYcp) {
        cPoints.add(new Point2D.Double(xCp, yCp));
        hasXcp = hasYcp = false;
        if (cPoints.size() == numCPs) {
          // Convert Catmull-Rom Spline into Cubic Bezier Curve in a Path2D object
          Point2D.Double[] points = cPoints.toArray(new Point2D.Double[cPoints.size()]);
          path.moveTo(points[0].x, points[0].y);
          int end = closed ? points.length + 1 : points.length - 1;
          for (int ii = 0;  ii < end - 1; ii++) {
            Point2D.Double p0, p1, p2, p3;
            if (closed) {
              int idx0 = Math.floorMod(ii - 1, points.length);
              int idx1 = Math.floorMod(idx0 + 1, points.length);
              int idx2 = Math.floorMod(idx1 + 1, points.length);
              int idx3 = Math.floorMod(idx2 + 1, points.length);
              p0 = new Point2D.Double(points[idx0].x, points[idx0].y);
              p1 = new Point2D.Double(points[idx1].x, points[idx1].y);
              p2 = new Point2D.Double(points[idx2].x, points[idx2].y);
              p3 = new Point2D.Double(points[idx3].x, points[idx3].y);
            } else {
              p0 = new Point2D.Double(points[Math.max(ii - 1, 0)].x, points[Math.max(ii - 1, 0)].y);
              p1 = new Point2D.Double(points[ii].x, points[ii].y);
              p2 = new Point2D.Double(points[ii + 1].x, points[ii + 1].y);
              p3 = new Point2D.Double(points[Math.min(ii + 2, points.length - 1)].x, points[Math.min(ii + 2, points.length - 1)].y);
            }
            // Catmull-Rom to Cubic Bezier conversion matrix
            //    0       1       0       0
            //  -1/6      1      1/6      0
            //    0      1/6      1     -1/6
            //    0       0       1       0
            double x1 = (-p0.x + 6 * p1.x + p2.x) / 6;  // First control point
            double y1 = (-p0.y + 6 * p1.y + p2.y) / 6;
            double x2 = ( p1.x + 6 * p2.x - p3.x) / 6;  // Second control point
            double y2 = ( p1.y + 6 * p2.y - p3.y) / 6;
            double x3 = p2.x;                           // End point
            double y3 = p2.y;
            path.curveTo(x1, y1, x2, y2, x3, y3);
          }
          if (closed) {
            path.closePath();
          }
        }
      }
    }
  }

  static class Vertex extends Entity {
    double xx, yy;

    Vertex (String type) {
      super(type);
    }

    @Override
    void addParm (int gCode, String value) {
      if (gCode == 10){
        xx = Double.parseDouble(value);
      } else if (gCode == 20) {
        yy = Double.parseDouble(value);
      }
    }
  }

  private void push () {
    stack.add(cEntity);
  }

  private void pop () {
    if (cEntity != null) {
      cEntity.close();
    }
    cEntity = stack.remove(stack.size() - 1);
  }

  private void addChildToTop (Entity child) {
    if (stack.size() > 0) {
      Entity top =  stack.get(stack.size() - 1);
      top.addChild(child);
    }
  }

  Shape[] parseFile (File file, double maxSize) throws IOException {
    stack = new ArrayList<>();
    hVariables = null;
    ArrayList<Shape> shapes = new ArrayList<>();
    cEntity = null;
    Scanner lines = new Scanner(new FileInputStream(file));
    while (lines.hasNextLine()) {
      String line = lines.nextLine().trim();
      String value = lines.nextLine().trim();
      int gCode = Integer.parseInt(line);
      switch (gCode) {
      case 0:                             // Entity type
        switch (value) {
        case "SECTION":
          cEntity = new Entity(null);
          break;
        case "ENDSEC":
          if (cEntity instanceof Header) {
            cEntity.close();
            hVariables = ((Header) cEntity).variables;
          }
          cEntity = null;
          stack.clear();
          break;
        case "TABLE":
          push();
          addChildToTop(cEntity = new Entity(value));
          break;
        case "ENDTAB":
          pop();
          break;
        case "BLOCK":
          push();
          addChildToTop(cEntity = new Entity(value));
          break;
        case "ENDBLK":
          pop();
          while ("BLOCK".equals(cEntity.type)) {
            pop();
          }
          break;
        case "SPLINE":
          if ("BLOCK".equals(cEntity.type)) {
            push();
          }
          Spline spline = new Spline(value);
          shapes.add(spline.path);
          addChildToTop(cEntity = spline);
          break;
        case "HATCH":
        case "INSERT":
          if ("BLOCK".equals(cEntity.type)) {
            push();
          }
          addChildToTop(cEntity = new Entity(value));
          break;
        case "POLYLINE":
          push();
          Polyline poly = new Polyline(value);
          shapes.add(poly.path);
          addChildToTop(cEntity = poly);
          break;
        case "VERTEX":
          if (cEntity != null && !"VERTEX".equals(cEntity.type)) {
            push();
          }
          addChildToTop(cEntity = new Vertex(value));
          break;
        case "SEQEND":
          while (stack.size() > 0 && !"BLOCK".equals(cEntity.type)) {
            pop();
          }
          break;
        default:
          cEntity = null;
          break;
        }
        break;
      case 2:                             // Entity Name
        if (cEntity != null) {
          if ("HEADER".equals(value)) {
            cEntity = new Header(value);
          } else {
            cEntity.setType(value);
          }
        }
        break;
      default:
        if (cEntity != null) {
          cEntity.addParm(gCode, value);
        }
        break;
      }
    }
    Rectangle2D bounds = null;
    for (Shape shape : shapes) {
      bounds = bounds == null ? shape.getBounds2D() : bounds.createUnion(shape.getBounds2D());
    }
    double scale = 1;
    double maxAxis = Math.max(bounds.getWidth(), bounds.getHeight());
    // Limit size to 10 inches on max dimension
    if (maxAxis > maxSize) {
      scale = maxSize / maxAxis;
    }
    // Scale, as needed, and flip Y axis
    AffineTransform at = new AffineTransform();
    at.scale(scale, -scale);
    at.translate(-bounds.getMinX(), -bounds.getHeight() -bounds.getMinY());
    Shape[] sOut = new Shape[shapes.size()];
    for (int ii = 0; ii < shapes.size(); ii++) {
      sOut[ii] = at.createTransformedShape(shapes.get(ii));
    }
    return sOut;
  }

  String getHeaderVariable (String name) {
    return hVariables != null ? hVariables.get(name) : "no header";
  }

  /*
   * Simple DXF Viewer to test the Parser
   */

  static class DXFViewer extends JFrame {
    private final double SCREEN_PPI = java.awt.Toolkit.getDefaultToolkit().getScreenResolution();
    private Shape[]      shapes;
    private double       border = 0.125;

    DXFViewer (Shape[] shapes) {
      this.shapes = shapes;
      Rectangle2D bounds = null;
      // Create a bounding box that's the union of all shapes in the shapes array
      for (Shape shape : shapes) {
        bounds = bounds == null ? shape.getBounds2D() : bounds.createUnion(shape.getBounds2D());
      }
      if (bounds != null) {
        int wid = (int) Math.round((bounds.getWidth() + border * 2) * SCREEN_PPI);
        int hyt = (int) Math.round((bounds.getHeight() + border * 2) * SCREEN_PPI);
        setSize(wid, hyt);
      }
      setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
      setResizable(false);
      setVisible(true);
    }

    public void paint (Graphics g) {
      Dimension d = getSize();
      Graphics2D g2 = (Graphics2D) g;
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g2.setBackground(getBackground());
      g2.clearRect(0, 0, d.width, d.height);
      g2.setColor(Color.black);
      AffineTransform atScale = new AffineTransform();
      atScale.translate(border * SCREEN_PPI, border * SCREEN_PPI);
      atScale.scale(SCREEN_PPI, SCREEN_PPI);
      for (Shape shape : shapes) {
        g2.draw(atScale.createTransformedShape(shape));
      }
      g2.drawString("Paths: " + shapes.length, 20, 30);
    }

  }

  public static void main (String[] args) throws Exception {
    DXFReader dxf = new DXFReader();
    Shape[] shapes = dxf.parseFile(new File(args[0]), 10.0);
    new DXFViewer(shapes);
  }
}