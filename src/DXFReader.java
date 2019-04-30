import javax.swing.*;
import java.awt.*;
import java.awt.font.GlyphVector;
import java.awt.font.TextAttribute;
import java.awt.geom.*;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.text.DecimalFormat;
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
 *  that this code will read all 2D DXF files.  Some instance variables are placeholders for features that have
 *  yet to be implmenented.
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
  private static final boolean  DEBUG = false;
  private static final boolean  INFO = false;
  private static final boolean  ANIMATE = false;
  private boolean               drawText;
  private boolean               drawMText;
  private boolean               drawDimen;
  private ArrayList<DrawItem>   entities = new ArrayList<>();
  private ArrayList<Entity>     stack = new ArrayList<>();
  private Map<String,Block>     blockDict = new TreeMap<>();
  private Entity                cEntity = null;
  private Rectangle2D           bounds;
  private double                uScale = 0.039370078740157; // default to millimeters as units
  private String                units = "unknown";
  private boolean               scaled, useMillimeters;

  interface AutoPop {}


  public DXFReader() {
    this(true);
  }

  public DXFReader (boolean useMillimeters) {
    this.useMillimeters = useMillimeters;
  }

  class Entity {
    private String        type;

    Entity (String type) {
      this.type = type;
    }

    // Override these methods is subclasses, as needed
    void addParm (int gCode, String value) { }

    void addChild (Entity child) { }

    void close () { }
  }

  class DrawItem extends Entity {

    DrawItem (String type) {
      super(type);
    }

    Shape getShape () {
      return null;
    }
  }

  class Section extends Entity {
    private Map<String,Map<Integer,String>>   attributes = new TreeMap<>();
    private Map<Integer,String>               attValues;
    private String                            sType;

    Section (String type) {
      super(type);
    }

    @Override
    void addParm (int gCode, String value) {
      if (gCode == 2 && sType == null) {
        sType = value;
      } else if (gCode == 9) {
        attValues = new HashMap<>();
        attributes.put(value, attValues);
      } else if (attValues != null) {
        attValues.put(gCode, value);
      }
    }
  }

  private void setUnits (String val) {
    if (val != null) {
      switch (Integer.parseInt(val)) {
      case 0:             // unitless (millimeters, or inches)
        uScale = useMillimeters ? 0.039370078740157 : 1.0;
        units = "unitless";
        break;
      case 1:             // inches
        uScale = 1.0;
        units = "inches";
        break;
      case 2:             // feet
        uScale = 1.0/12;
        units = "feet";
        break;
      case 3:             // miles
        uScale = 63360.0;
        units = "miles";
        break;
      case 4:             // millimeters
        uScale = 0.039370078740157;
        units = "millimeters";
        break;
      case 5:             // centimeters
        uScale = 0.39370078740157;
        units = "centimeters";
        break;
      case 6:             // meters
        uScale = 39.370078740157;
        units = "meters";
        break;
      case 7:             // kilometers
        uScale = 39370.078740157;
        units = "kilometers";
        break;
      case 8:             // microinches
        uScale = 0.000001;
        units = "microinches";
        break;
      case 9:             // mils
        uScale = 0.001;
        units = "mils";
        break;
      case 10:            // yards
        uScale = 36.0;
        units = "yards";
        break;
      case 11:            // angstroms
        uScale = 3.9370078740157e-9;
        units = "angstroms";
        break;
      case 12:            // nanometers
        uScale = 3.9370078740157e-8;
        units = "nanometers";
        break;
      case 13:            // microns
        uScale = 3.9370078740157e-5;
        units = "microns";
        break;
      case 14:            // decimeters
        uScale = 3.9370078740157;
        units = "decimeters";
        break;
      case 15:            // decameters
        uScale = 393.70078740157;
        units = "decameters";
        break;
      case 16:            // hectometers
        uScale = 3937.007878740157;
        units = "hectometers";
        break;
      case 17:            // gigameters
        uScale = 39370078740.157;
        units = "gigameters";
        break;
      case 18:            // astronomical units
        uScale = 5.89e+12;
        units = "astronomical units";
        break;
      case 19:            // light years
        uScale = 3.725e+17;
        units = "light years";
        break;
      case 20:            // parsecs
        uScale = 1.215e+18;
        units = "parsecs";
        break;
      }
    }
  }

  // Text code
  private void addX (Path2D.Double path, double cx, double cy, double tenth) {
    path.moveTo(cx - tenth, cy - tenth);
    path.lineTo(cx + tenth, cy + tenth);
    path.moveTo(cx + tenth, cy - tenth);
    path.lineTo(cx - tenth, cy + tenth);
  }

  // Provides a way to disable drawing of certain types
  private boolean doDraw (DrawItem entity) {
    if ((entity instanceof Text && !drawText) ||
        (entity instanceof MText && !drawMText) ||
        (entity instanceof Dimen && !drawDimen)) {
      return false;
    }
    return true;
  }

  /**
   * Enables drawing og TEXT objects (disabled by default)
   * @param enable
   */
  public void setDrawText (boolean enable) {
    drawText = enable;
  }

  /**
   * Enables drawing og MTEXT objects (disabled by default)
   * @param enable
   */
  public void setDrawMText (boolean enable) {
    drawMText = enable;
  }

  /**
   * Enables drawing og DIMENSION objects (disabled by default)
   * @param enable
   */
  public void setDrawDimen (boolean enable) {
    drawDimen = enable;
  }

  /**
   * Crude implementation of TEXT using GlyphVector to create vector outlines of text
   * Note: this code should use, or support vector fonts such as those by Hershey
   */
  class Text extends DrawItem implements AutoPop {
    private Canvas    canvas = new Canvas();
    private double    ix, iy, ix2, iy2, textHeight, rotation;
    private int       hAdjust, vAdjust;
    private String    text;

    Text (String type) {
      super(type);
    }

    @Override
    void addParm (int gCode, String value) {
      switch (gCode) {
      case 1:                                       // Text string
        // Process Control Codes and Special Chars
        // https://forums.autodesk.com/t5/autocad-forum/text-commands-eg-u/td-p/1977654
        StringBuilder buf = new StringBuilder();
        for (int ii = 0; ii < value.length(); ii++) {
          char cc = value.charAt(ii);
          if (cc == '%') {
            cc = value.charAt(ii + 2);
            ii += 2;
            if (Character.isDigit(cc)) {
              int code = 0;
              while (Character.isDigit(cc = value.charAt(ii))) {
                code = (code * 10) + (cc - '0');
                ii++;
              }
              // todo: how to convert value of "code" into special character
              buf.append("\uFFFD");                 // Insert Unicode unknown character symbol
              ii--;
            } else {
            switch (cc) {
              case 'u':                             // Toggles underscoring on and off
                // Ignored
                break;
              case 'd':                             // Draws degrees symbol (°)
                buf.append("\u00B0");
                break;
              case 'p':                             // Draws plus/minus tolerance symbol (±)
                buf.append("\u00B1");
                break;
              case 'c':                             // Draws circle diameter dimensioning symbol (Ø)
                buf.append("\u00D8");
                break;
              case 'o':                             // Toggles overscoring on and off
                // Ignored
                break;
              }
            }
          } else {
            buf.append(cc);
          }
        }
        text = buf.toString();
        break;
      case 10:                                      // Insertion X
        ix = Double.parseDouble(value) * uScale;
        break;
      case 11:                                      // Second alignment point X
        ix2 = Double.parseDouble(value) * uScale;
        break;
      case 20:                                      // Insertion Y
        iy = Double.parseDouble(value) * uScale;
        break;
      case 21:                                      // Second alignment point Y
        iy2 = Double.parseDouble(value) * uScale;
        break;
      case 40:                                      // Nominal (initial) text height
        textHeight = Double.parseDouble(value) * uScale;
        break;
      case 50:                                      // Rotation angle in degrees
        rotation = Double.parseDouble(value);
        break;
      case 71:                                      // Text generation flags (optional, default = 0):
        // Not implemented
        // 2 = Text is backward (mirrored in X)
        // 4 = Text is upside down (mirrored in Y)
        break;
      case 72:                                      // Horizontal text justification type (optional, default = 0) integer codes
        //0 = Left; 1= Center; 2 = Right
        //3 = Aligned (if vertical alignment = 0)
        //4 = Middle (if vertical alignment = 0)
        //5 = Fit (if vertical alignment = 0)
        hAdjust = Integer.parseInt(value);
        break;
      case 73:                                      // Vertical text justification type (optional, default = 0): integer codes
        // 0 = Baseline; 1 = Bottom; 2 = Middle; 3 = Top
        vAdjust = Integer.parseInt(value);
        break;
      }
    }

    @Override
    Shape getShape () {
      if (false) {
        // Test code
        Path2D.Double path = new Path2D.Double();
        // Draw 'X' as placeholder for MTEXT at definition midpoint
        if (hAdjust != 0 || vAdjust != 0) {
          addX(path, ix2, iy2, 4 * uScale);
        } else {
          addX(path, ix, iy, 4 * uScale);
        }
        return path;
      } else {
        // Note: I had to scale up font size by 10x to make it render properly
        float points = (float) textHeight * 10f;
        Font font = (new Font("Helvetica", Font.PLAIN, 72)).deriveFont(points);
        HashMap<TextAttribute, Object> attrs = new HashMap<>();
        attrs.put(TextAttribute.KERNING, TextAttribute.KERNING_ON);
        attrs.put(TextAttribute.LIGATURES, TextAttribute.LIGATURES_ON);
        attrs.put(TextAttribute.TRACKING, 0.1);
        font = font.deriveFont(attrs);
        GlyphVector gv = font.createGlyphVector(canvas.getFontMetrics(font).getFontRenderContext(), text);
        // Step 1 - Convert GlyphVector to Shape
        AffineTransform at1 = new AffineTransform();
        Shape shape = at1.createTransformedShape(gv.getOutline());
        Rectangle2D bnds = shape.getBounds2D();
        // Step 2 - Translate shape according to vAdjust and hAdjust values
        AffineTransform at2 = new AffineTransform();
        // TODO: test all attachment point cases
        if (vAdjust == 3 && hAdjust == 0) {                             // Top left
          at2.translate(0, bnds.getHeight());
        } else if (vAdjust == 3 && hAdjust == 1) {                      // Top center
          at2.translate(-bnds.getWidth() / 2, bnds.getHeight());
        } else if (vAdjust == 3 && hAdjust == 2) {                      // Top right
          at2.translate(-bnds.getWidth(), bnds.getHeight());
        } else if (vAdjust == 2 && hAdjust == 0) {                      // Middle left
          at2.translate(0, bnds.getHeight() / 2);
        } else if (vAdjust == 2 && hAdjust == 1) {                      // Middle center
          at2.translate(-bnds.getWidth() / 2, bnds.getHeight() / 2);
        } else if (vAdjust == 2 && hAdjust == 2) {                      // Middle right
          at2.translate(-bnds.getWidth(), bnds.getHeight() / 2);
        } else if (vAdjust == 1 && hAdjust == 0) {                      // Bottom left (natural position)
          at2.translate(0, 0);
        } else if (vAdjust == 1 && hAdjust == 1) {                      // Bottom center
          at2.translate(-bnds.getWidth() / 2, 0);
        } else if (vAdjust == 1 && hAdjust == 2) {                      // Bottom right
          at2.translate(-bnds.getWidth(), 0);
        }
        shape = at2.createTransformedShape(shape);
        // Step 3 - Rotate and Scale shape
        AffineTransform at3 = new AffineTransform();
        at3.rotate(Math.toRadians(rotation));
        at3.scale(.1, -.1);
        shape = at3.createTransformedShape(shape);
        // Step 4 - Translate shape to final position
        AffineTransform at4 = new AffineTransform();
        if (hAdjust != 0 || vAdjust != 0) {
          at4.translate(ix2, iy2);
        } else {
          at4.translate(ix, iy);
        }
        shape = at4.createTransformedShape(shape);
        return shape;
      }
    }
  }

  /**
   * Crude implementation of MTEXT (Multi-line Text) using GlyphVector to create vector outline of text
   * Note: the MTEXT spec is very complex and assumes the ability to decode embedded format codes, use vector fonts
   * such as those by Hershey, and other features I have not implemented.
   * https://knowledge.safe.com/articles/38908/autocad-workflows-reading-and-writing-text-mtext-f.html
   *
   * Example Text with Format Codes: https://adndevblog.typepad.com/autocad/2017/09/dissecting-mtext-format-codes.html
   *  \A1;3'-1"
   *  \A1;6'-10{\H0.750000x;\S1/2;}"
   *  \A1;PROVIDE 20 MIN. DOOR\PW/ SELF CLOSING HINGES
   *  {\Farchquik.shx|c0;MIN. 22"x 30" ATTIC ACCESS}
   *  "HEATILATOR" 42" GAS BURNING DIRECT VENT FIREPLACE, OR EQUAL
   *  BOLLARD,\PFOR W.H.\PPROTECTION
   */
  class MText extends DrawItem implements AutoPop {
    private Canvas    canvas = new Canvas();
    private String    text, textStyle;
    private double    ix, iy, textHeight, refWidth, xRot, yRot;
    private int       attachPoint;

    MText (String type) {
      super(type);
    }
    @Override
    void addParm (int gCode, String value) {
      switch (gCode) {
      case 1:                                         // Text string
        // Process Format Codes (most are ignored)
        List<String> lines = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (int jj = 0; jj < value.length(); jj++) {
          char cc = value.charAt(jj);
          if (cc == '\\') {
            cc = value.charAt(++jj);
            switch (cc) {
              case 'A':                               // Alignment
              case 'C':                               // Color
              case 'F':                               // Font file name
              case 'H':                               // Text height
              case 'Q':                               // Slanting (obliquing) text by angle
              case 'S':                               // Stacking Fractions
              case 'T':                               // Tracking, char.spacing - e.g. \T2;
              case 'W':                               // Text width
                int tdx = value.indexOf(";", jj);
                String val = value.substring(jj + 1, tdx);
                jj = tdx;
                if (cc == 'S') {                      // Stacking Fractions (1/2, 1/3, etc)
                  if ("1/2".equals(val)) {
                    buf.append("\u00BD");             // Unicode for 1/2
                  } else if ("1/3".equals(val)) {
                      buf.append("\u2153");           // Unicode for 1/3
                  } else if ("1/4".equals(val)) {
                    buf.append("\u00BC");             // Unicode for 1/4
                  } else if ("2/3".equals(val)) {
                    buf.append("\u2154");             // Unicode for 2/3
                  } else if ("3/4".equals(val)) {
                    buf.append("\u00BE");             // Unicode for 3/4
                  } else {
                    String[] parts = val.split("/");
                    if (parts.length == 2) {
                      buf.append(parts[0]);
                      buf.append("\u2044");
                      buf.append(parts[1]);
                    }
                  }
                }
                break;
              case 'P':                               // New paragraph (new line)
                lines.add(buf.toString());
                buf.setLength(0);
                break;
              case '\\':                              // Escape character - e.g. \\ = "\", \{ = "{"
                buf.append(value.charAt(++jj));
                break;
            }
          } else if (cc == '{') {
            // Begin area influenced by special code
          } else if (cc == '}') {
            // End area influenced by special code
          } else {
            buf.append(cc);
          }
        }
        lines.add(buf.toString());
        // Skip handling all but first line of text
        text  = lines.get(0);
        if (text.length() > 30 && refWidth > 0) {
          // KLudge until code to handle "refWidth" is added
          text = text.substring(0, 30) + "...";
        }
        break;
      case 7:                                       // Text style name (STANDARD if not provided) (optional)
        textStyle = value;
        break;
      case 10:                                      // Insertion X
        ix = Double.parseDouble(value) * uScale;
        break;
      case 11:                                      // X Rotation Unit Vector
        xRot = Double.parseDouble(value);
        break;
      case 20:                                      // Insertion Y
        iy = Double.parseDouble(value) * uScale;
        break;
      case 21:                                      // Y Rotation Unit Vector
        yRot = Double.parseDouble(value);
        break;
      case 40:                                      // Nominal (initial) text height
        textHeight = Double.parseDouble(value) * uScale;
        break;
      case 41:                                      // Reference rectangle width
        refWidth = Double.parseDouble(value) * uScale;
        break;
      case 71:                                      // Attachment point
        attachPoint = Integer.parseInt(value);
        break;
      case 72:                                      // Drawing direction: 1 = Left to right; 3 = Top to bottom; 5 = By style
        break;
      }
    }

    @Override
    Shape getShape () {
      if (false) {
        // Test code
        Path2D.Double path = new Path2D.Double();
        // Draw 'X' as placeholder for MTEXT at definition midpoint
        addX(path, ix, iy, 1 * uScale);
        return path;
      } else {
        // Note: I had to scale up font size by 10x to make it render properly
        float points = (float) textHeight * 10f;
        Font font = (new Font("Helvetica", Font.PLAIN, 72)).deriveFont(points);
        HashMap<TextAttribute, Object> attrs = new HashMap<>();
        attrs.put(TextAttribute.KERNING, TextAttribute.KERNING_ON);
        attrs.put(TextAttribute.LIGATURES, TextAttribute.LIGATURES_ON);
        attrs.put(TextAttribute.TRACKING, 0.1);
        font = font.deriveFont(attrs);
        GlyphVector gv = font.createGlyphVector(canvas.getFontMetrics(font).getFontRenderContext(), text);
        // Step 1 - Convert GlyphVector to Shape
        AffineTransform at1 = new AffineTransform();
        Shape shape = at1.createTransformedShape(gv.getOutline());
        Rectangle2D bnds = shape.getBounds2D();
        // Step 2 - Translate shape according to Attachment Point value
        AffineTransform at2 = new AffineTransform();
        // TODO: test all attachment point cases
        switch (attachPoint) {
          case 1:                                 // Top left
            at2.translate(0, bnds.getHeight());
            break;
          case 2:                                 // Top center
            at2.translate(-bnds.getWidth() / 2, bnds.getHeight());
            break;
          case 3:                                 // Top right
            at2.translate(-bnds.getWidth(), bnds.getHeight());
            break;
          case 4:                                 // Middle left
            at2.translate(0, bnds.getHeight() / 2);
            break;
          case 5:                                 // Middle center
            at2.translate(-bnds.getWidth() / 2, bnds.getHeight() / 2);
            break;
          case 6:                                 // Middle right
            at2.translate(-bnds.getWidth(), bnds.getHeight() / 2);
            break;
          case 7:                                 // Bottom left (natural position)
            at2.translate(0, 0);
            break;
          case 8:                                 // Bottom center
            at2.translate(-bnds.getWidth() / 2, 0);
            break;
          case 9:                                 // Bottom right
            at2.translate(-bnds.getWidth(), 0);
            break;
        }
        shape = at2.createTransformedShape(shape);
        // Step 3 - Rotate and Scale shape
        AffineTransform at3 = new AffineTransform();
        double rotation = Math.atan2(yRot, xRot);
        at3.rotate(rotation);
        at3.scale(.1, -.1);
        shape = at3.createTransformedShape(shape);
        // Step 4 - Translate shape to final position
        AffineTransform at4 = new AffineTransform();
        at4.translate(ix, iy);
        shape = at4.createTransformedShape(shape);
        return shape;
      }
    }
  }

  class Block extends Entity {
    private String          name, handle;
    private List<DrawItem>  entities = new ArrayList<>();
    private double          baseX, baseY;
    private int             flags;

    Block (String type) {
      super(type);
    }

    @Override
    void addParm (int gCode, String value) {
      switch (gCode) {
      case 2:                                       // Block name
        name  = value;
        blockDict.put(name, this);
        break;
      case 5:                                       // Block handle
        handle = value;
        break;
      case 10:                                      // Base Point X
        baseX = Double.parseDouble(value) * uScale;
        break;
      case 20:                                      // Base Point Y
        baseY = Double.parseDouble(value) * uScale;
        break;
      case 70:                                      // Flags
        flags = Integer.parseInt(value);
        break;
      }
    }

    void addEntity (DrawItem entity) {
      entities.add(entity);
    }
  }

  // TODO: implement when I understand how this is supposed to work...
  class Hatch extends DrawItem implements AutoPop {
    Hatch (String type) {
      super(type);
    }
  }

  class Insert extends DrawItem implements AutoPop {
    private String    blockHandle, blockName;
    private double    ix, iy, xScale = 1.0, yScale = 1.0, zScale = 1.0, rotation;

    Insert (String type) {
      super(type);
    }

    @Override
    void addParm (int gCode, String value) {
      switch (gCode) {
      case 2:                                     // Name of Block to insert
        blockName = value;
        break;
      case 5:                                     // Handle of Block to insert
        blockHandle = value;
        break;
      case 10:                                    // Insertion X
        ix = Double.parseDouble(value) * uScale;
        break;
      case 20:                                    // Insertion Y
        iy = Double.parseDouble(value) * uScale;
        break;
      case 41:                                    // X scaling
        xScale = Double.parseDouble(value);
        break;
      case 42:                                    // Y scaling
        yScale = Double.parseDouble(value);
        break;
      case 43:                                    // Z Scaling (affects x coord and rotation)
        zScale = Double.parseDouble(value);
        break;
      case 50:                                    // Rotation angle (degrees)
        rotation = Double.parseDouble(value);
        break;
      }
    }

    @Override
    Shape getShape () {
      Block block = blockDict.get(blockName);
      if (block != null && block.entities.size() > 0) {
        Path2D.Double path = new Path2D.Double();
        AffineTransform at1 = null;
        if (block.baseX != 0 || block.baseY != 0) {
          // TODO: make this work...
          at1 = new AffineTransform();
          at1.translate(block.baseX, block.baseY);
        }
        AffineTransform at2 = new AffineTransform();
        if (zScale < 0) {
          // Fixes "DXF Files that do not Render Properly/Floor plan.dxf" test file
          at2.translate(-ix, iy);
          at2.scale(-xScale, yScale);
        } else {
          at2.translate(ix, iy);
          at2.scale(xScale, yScale);
        }
        at2.rotate(Math.toRadians(xScale < 0 ? - rotation : rotation));
        for (DrawItem entity : block.entities) {
          if (doDraw(entity)) {
            Shape shape = entity.getShape();
            if (shape != null) {
              if (at1 != null) {
                // TODO: make this work...
                shape = at1.createTransformedShape(shape);
              }
              shape = at2.createTransformedShape(shape);
              path.append(shape, false);
            }
          }
        }
        return path;
      }
      return null;
    }
  }

  /*
   * Note: code for "DIMENSION" is incomplete
   */
  class Dimen extends DrawItem implements AutoPop {
    private String    blockHandle, blockName;
    private double    ax, ay, mx, my;
    private int       type, orientation;

    Dimen (String type) {
      super(type);
    }

    @Override
    void addParm (int gCode, String value) {
      switch (gCode) {
      case 2:                                     // Name of Block to with Dimension graphics
        blockName = value;
        break;
      case 5:                                     // Handle of Block to with Dimension graphics
        blockHandle = value;
        break;
      case 10:                                    // Definition Point X
        ax = Double.parseDouble(value) * uScale;
        break;
      case 20:                                    // Definition Point Y
        ay = Double.parseDouble(value) * uScale;
        break;
      case 11:                                    // Mid Point X
        mx = Double.parseDouble(value) * uScale;
        break;
      case 21:                                    // Mid Point Y
        my = Double.parseDouble(value) * uScale;
        break;
      case 70:                                    // Dimension type (0-6 plus bits at 32,64,128)
        type = Integer.parseInt(value);
        break;
      case 71:                                    // Attachment orientation (1-9) for 1=UL, 2=UC, 3=UR, etc
        orientation = Integer.parseInt(value);
        break;
      }
    }

    @Override
    Shape getShape () {
      Block block = blockDict.get(blockName);
      if (block != null && block.entities.size() > 0) {
        Path2D.Double path = new Path2D.Double();
        for (DrawItem entity : block.entities) {
          Shape shape = entity.getShape();
          if (shape != null) {
            path.append(shape, false);
          }
        }
        return path;
      }
      return null;
    }
  }

  class Circle extends DrawItem implements AutoPop {
    Ellipse2D.Double  circle = new Ellipse2D.Double();
    private double    cx, cy, mx, my, radius;

    Circle (String type) {
      super(type);
    }

    @Override
    void addParm (int gCode, String value) {
      switch (gCode) {
        case 10:                                  // Center Point X1
          cx = Double.parseDouble(value) * uScale;
          break;
        case 20:                                  // Center Point Y2
          cy = Double.parseDouble(value) * uScale;
          break;
        case 40:                                  // Radius
          radius = Double.parseDouble(value) * uScale;
          break;
      }
    }

    @Override
    Shape getShape () {
      return circle;
    }

    @Override
    void close () {
      circle.setFrame(cx - radius, cy - radius, radius * 2, radius * 2);
    }
  }

  /**
   * Crude implementation of ELLIPSE
   * Note: does not currently handle Start and End Parameters
   */
  class Ellipse extends DrawItem implements AutoPop {
    Ellipse2D.Double  ellipse = new Ellipse2D.Double();
    private Shape     shape;
    private double    cx, cy, mx, my, ratio, start, end;

    Ellipse (String type) {
      super(type);
    }

    @Override
    void addParm (int gCode, String value) {
      switch (gCode) {
      case 10:                                  // Center Point X1
        cx = Double.parseDouble(value) * uScale;
        break;
      case 11:                                  // Endpoint of major axis X
        mx = Double.parseDouble(value) * uScale;
        break;
      case 20:                                  // Center Point Y2
        cy = Double.parseDouble(value) * uScale;
        break;
      case 21:                                  // Endpoint of major axis Y
        my = Double.parseDouble(value) * uScale;
        break;
      case 40:                                  // Ratio of minor axis to major axis
        ratio = Double.parseDouble(value);
        break;
      case 41:                                  // Start parameter (this value is 0.0 for a full ellipse)
        start = Double.parseDouble(value);
        break;
      case 42:                                  // End parameter (this value is 2pi for a full ellipse)
        end = Double.parseDouble(value);
        break;
      }
    }

    @Override
    Shape getShape () {
      if (false) {
        // Test code
        Path2D.Double path = new Path2D.Double();
        // Draw center point
        addX(path, cx, cy, .2 * uScale);
        // Draw Endpoint of major axis
        addX(path, cx + mx, cy + my, .1 * uScale);
        // Add ellipse
        path.append(shape, false);
        return path;
      } else {
        return shape;
      }
    }

    @Override
    void close () {
      double hoff = Math.abs(Math.sqrt(mx * mx + my * my));
      double voff = Math.abs(hoff * ratio);
      ellipse.setFrame(-hoff, -voff, hoff * 2, voff * 2);
      double angle = Math.atan2(my, mx);
      AffineTransform at = new AffineTransform();
      at.translate(cx, cy);
      at.rotate(angle);
      shape = at.createTransformedShape(ellipse);
    }
  }

  class Arc extends DrawItem implements AutoPop {
    Arc2D.Double arc = new Arc2D.Double(Arc2D.OPEN);
    private double    cx, cy, startAngle, endAngle, radius;

    Arc (String type) {
      super(type);
    }

    @Override
    void addParm (int gCode, String value) {
      switch (gCode) {
      case 10:                                  // Center Point X1
        cx = Double.parseDouble(value) * uScale;
        break;
      case 20:                                  // Center Point Y2
        cy = Double.parseDouble(value) * uScale;
        break;
      case 40:                                  // Radius
        radius = Double.parseDouble(value) * uScale;
        break;
      case 50:                                  // Start Angle
        startAngle = Double.parseDouble(value);
        break;
      case 51:                                  // End Angle
        endAngle = Double.parseDouble(value);
        break;
      }
    }

    @Override
    Shape getShape () {
      return arc;
    }

    @Override
    void close () {
      arc.setFrame(cx - radius, cy - radius, radius * 2, radius * 2);
      // Make angle negative so it runs clockwise when using Arc2D.Double
      arc.setAngleStart(-startAngle);
      double extent = startAngle - (endAngle < startAngle ? endAngle + 360 : endAngle);
      arc.setAngleExtent(extent);
    }
  }

  class Line extends DrawItem implements AutoPop {
    Path2D.Double         path = new Path2D.Double();
    private double        xStart, yStart, xEnd, yEnd;

    Line (String type) {
      super(type);
    }

    @Override
    void addParm (int gCode, String value) {
      switch (gCode) {
      case 10:                              // Line Point X1
        xStart = Double.parseDouble(value) * uScale;
        break;
      case 20:                              // Line Point Y2
        yStart = Double.parseDouble(value) * uScale;
        break;
      case 11:                              // Line Point X2
        xEnd = Double.parseDouble(value) * uScale;
        break;
      case 21:                              // Line Point Y2
        yEnd = Double.parseDouble(value) * uScale;
        break;
      }
    }

    @Override
    void close () {
      path.moveTo(xStart, yStart);
      path.lineTo(xEnd, yEnd);
    }

    @Override
    Shape getShape () {
      return path;
    }
  }

  class Polyline extends DrawItem {
    private Path2D.Double   path;
    private List<Vertex>    points;
    private double          firstX, firstY, lastX, lastY;
    private boolean         firstPoint = true;
    private boolean         close;

    Polyline (String type) {
      super(type);
    }

    @Override
    void addParm (int gCode, String value) {
      if (gCode == 70) {
        int flags = Integer.parseInt(value);
        close = (flags & 1) != 0;
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
    Shape getShape () {
      return path;
    }

    @Override
    void close () {
      path = new Path2D.Double();
      double bulge = 0.0;
      for (Vertex vertex : points) {
        if (firstPoint) {
          firstPoint = false;
          path.moveTo(firstX = lastX = vertex.xx, firstY = lastY = vertex.yy);
        } else {
          if (bulge != 0) {
            path.append(getArcBulge(lastX, lastY, vertex.xx, vertex.yy, bulge), true);
            lastX = vertex.xx;
            lastY = vertex.yy;
          } else {
            path.lineTo(lastX = vertex.xx, lastY = vertex.yy);
          }
        }
        bulge = vertex.bulge;
      }
      if (close) {
        if (bulge != 0) {
          path.append(getArcBulge(lastX, lastY, firstX, firstY, bulge), true);
        } else {
          path.closePath();
        }
      }
    }
  }

  class Vertex extends Entity {
    double xx, yy, bulge;

    Vertex (String type) {
      super(type);
    }

    @Override
    void addParm (int gCode, String value) {
      switch (gCode) {
      case 10:                                    // Vertex X
        xx = Double.parseDouble(value) * uScale;
        break;
      case 20:                                    // Vertex Y
        yy = Double.parseDouble(value) * uScale;
        break;
      case 42:                                    // Vertex Bulge factor
        bulge =  Double.parseDouble(value);
        break;
      }
    }
  }

  class LwPolyline extends DrawItem implements AutoPop {
    Path2D.Double         path;
    List<LSegment>        segments = new ArrayList<>();
    LSegment              cSeg;
    private int           vertices;
    private double        xCp, yCp;
    private boolean       hasXcp, hasYcp;
    private boolean       close;

    class LSegment {
      private double  dx, dy, bulge;

      LSegment (double dx, double dy) {
        this.dx = dx;
        this.dy = dy;
      }
    }

    LwPolyline (String type) {
      super(type);
    }

    @Override
    void addParm (int gCode, String value) {
      switch (gCode) {
      case 10:                                      // Control Point X
        xCp = Double.parseDouble(value) * uScale;
        hasXcp = true;
        break;
      case 20:                                      // Control Point Y
        yCp = Double.parseDouble(value) * uScale;
        hasYcp = true;
        break;
      case 70:                                      // Flags
        int flags = Integer.parseInt(value);
        close = (flags & 0x01) != 0;
        break;
      case 42:                                      // Bulge factor  (positive = right, negative = left)
        cSeg.bulge = Double.parseDouble(value);
        if ((1 - Math.abs(cSeg.bulge)) < .001) {
          int dum = 0;
        }
        break;
      case 90:                                      // Number of Vertices
        vertices = Integer.parseInt(value);
        break;
      }
      if (hasXcp && hasYcp) {
        hasXcp = hasYcp = false;
        segments.add(cSeg = new LSegment(xCp, yCp));
      }
    }

    @Override
    Shape getShape () {
      return path;
    }

    @Override
    void close () {
      path = new Path2D.Double();
      boolean first = true;
      double lastX = 0, lastY = 0, firstX = 0, firstY = 0;
      double bulge = 0;
      for (LSegment seg : segments) {
        if (bulge != 0) {
          path.append(getArcBulge(lastX, lastY, lastX = seg.dx, lastY = seg.dy, bulge), true);
        } else {
          if (first) {
            path.moveTo(firstX = lastX = seg.dx, firstY = lastY = seg.dy);
            first = false;
          } else {
            path.lineTo(lastX = seg.dx, lastY = seg.dy);
          }
        }
        bulge = seg.bulge;
      }
      if (close) {
        if (bulge != 0) {
          path.append(getArcBulge(lastX, lastY, firstX, firstY, bulge), true);
        } else {
          path.lineTo(firstX, firstY);
        }
      }
    }
  }

  class Spline extends DrawItem implements AutoPop {
    Path2D.Double         path = new Path2D.Double();
    List<Point2D.Double>  cPoints = new ArrayList<>();
    private double        xCp, yCp;
    private boolean       hasXcp, hasYcp;
    private boolean       closed;
    private int           numCPs, flags;
    private boolean       hasMoveTo;

    Spline (String type) {
      super(type);
    }

    @Override
    void addParm (int gCode, String value) {
      switch (gCode) {
      case 10:                                    // Control Point X
        xCp = Double.parseDouble(value) * uScale;
        hasXcp = true;
        break;
      case 20:                                    // Control Point Y
        yCp = Double.parseDouble(value) * uScale;
        hasYcp = true;
        break;
      case 70:                                    // Flags
        flags = Integer.parseInt(value);
        closed = (flags & 0x01) != 0;
        break;
      case 73:                                    // Number of Control Points
        numCPs = Integer.parseInt(value);
        break;
      default:
        //System.out.println("Spline.addParm() unimplemented gCode: " + gCode + ", val: " + value);
        break;
      }
      if (hasXcp && hasYcp) {
        cPoints.add(new Point2D.Double(xCp, yCp));
        hasXcp = hasYcp = false;
        if (cPoints.size() == numCPs) {
          // Convert Catmull-Rom Spline into Cubic Bezier Curve in a Path2D object
          Point2D.Double[] points = cPoints.toArray(new Point2D.Double[0]);
          if (!hasMoveTo) {
            path.moveTo(points[0].x, points[0].y);
            hasMoveTo = true;
          }
          int end = closed ? points.length + 1 : points.length;
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
        }
      }
    }

    @Override
    Shape getShape () {
      if (closed) {
        path.closePath();
        closed = false;
      }
      return path;
    }
  }


  /**
   *  See: http://darrenirvine.blogspot.com/2015/08/polylines-radius-bulge-turnaround.html
   * @param sx Starting x for Arc
   * @param sy Starting y for Arc
   * @param ex Ending x for Arc
   * @param ey Ending y for Arc
   * @param bulge bulge factor (bulge > 0 = clockwise, else counterclockwise)
   * @return Arc2D.Double object
   */
  private Arc2D.Double getArcBulge (double sx, double sy, double ex, double ey, double bulge) {
    Point2D.Double p1 = new Point2D.Double(sx, sy);
    Point2D.Double p2 = new Point2D.Double(ex, ey);
    Point2D.Double mp = new Point2D.Double((p2.x + p1.x) / 2, (p2.y + p1.y) / 2);
    Point2D.Double bp = new Point2D.Double(mp.x - (p1.y - mp.y) * bulge, mp.y + (p1.x - mp.x) * bulge);
    double u = p1.distance(p2);
    double b = (2 * mp.distance(bp)) / u;
    double radius = u * ((1 + b * b) / (4 * b));
    double dx = mp.x - bp.x;
    double dy = mp.y - bp.y;
    double mag = Math.sqrt(dx * dx + dy * dy);
    Point2D.Double cp = new Point2D.Double(bp.x + radius * (dx / mag), bp.y + radius * (dy / mag));
    double startAngle = 180 - Math.toDegrees(Math.atan2(cp.y - p1.y, cp.x - p1.x));
    double opp = u / 2;
    double extent = Math.toDegrees(Math.asin(opp / radius)) * 2;
    double extentAngle = bulge >= 0 ? -extent : extent;
    Point2D.Double ul = new Point2D.Double(cp.x - radius, cp.y - radius);
    return new Arc2D.Double(ul.x, ul.y, radius * 2, radius * 2, startAngle, extentAngle, Arc2D.OPEN);
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
      if (top != null) {
        top.addChild(child);
      }
    }
  }

  private void addEntity (DrawItem entity) {
    if (cEntity instanceof Block) {
      Block block = (Block) cEntity;
      if (entity instanceof Insert && (block.flags & 2) != 0) {
        push();
        entities.add(entity);
        cEntity = entity;
      } else {
        push();
        block.addEntity(entity);
        cEntity = entity;
      }
    } else {
      push();
      entities.add(entity);
      cEntity = entity;
    }
  }

  private void debugPrint (String value) {
    for (int ii = 0; ii < stack.size(); ii++) {
      System.out.print("  ");
    }
    System.out.println(value);
  }

  Shape[] parseFile (File file, double maxSize, double minSize) throws IOException {
    stack = new ArrayList<>();
    cEntity = null;
    Scanner lines = new Scanner(new FileInputStream(file));
    while (lines.hasNextLine()) {
      String line = lines.nextLine().trim();
      String value = lines.nextLine().trim();
      int gCode = Integer.parseInt(line);
      switch (gCode) {
      case 0:                             // Entity type
        if (cEntity instanceof AutoPop) {
          pop();
        }
        if (DEBUG) {
          debugPrint(value);
        }
        switch (value) {
        case "SECTION":
          cEntity = new Section(value);
          break;
        case "ENDSEC":
          if (cEntity instanceof Section) {
            Section section = (Section) cEntity;
            if ("HEADER".equals(section.sType)) {
              Map<Integer,String> attrs = section.attributes.get("$INSUNITS");
              if (attrs != null) {
                String units = attrs.get(70);
                setUnits(units);
              }
              attrs = section.attributes.get("$LUNITS");
              if (attrs != null) {
                String units = attrs.get(70);
                setUnits(units);
              }
            }
          }
          cEntity = null;
          stack.clear();
          break;
        case "TABLE":
          push();
          cEntity = new Entity(value);
          break;
        case "ENDTAB":
          pop();
          break;
        case "BLOCK":
          push();
          cEntity = new Block(value);
          break;
        case "ENDBLK":
          pop();
          while ("BLOCK".equals(cEntity.type)) {
            pop();
          }
          break;
        case "SPLINE":
          addEntity(new Spline(value));
          break;
        case "INSERT":
          addEntity(new Insert(value));
          break;
        case "TEXT":
          addEntity(new Text(value));
          break;
        case "MTEXT":
          addEntity(new MText(value));
          break;
        case "HATCH":
          addEntity(new Hatch(value));
          break;
        case "CIRCLE":
          addEntity(new Circle(value));
          break;
        case "ELLIPSE":
          addEntity(new Ellipse(value));
          break;
        case "ARC":
          addEntity(new Arc(value));
          break;
        case "LINE":
          addEntity(new Line(value));
          break;
        case "DIMENSION":
          addEntity(new Dimen(value));
          break;
        case "POLYLINE":
          addEntity(new Polyline(value));
          break;
        case "LWPOLYLINE":
          addEntity( new LwPolyline(value));
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
        }
        break;
      default:
        if (cEntity != null) {
          if (DEBUG) {
            debugPrint(gCode + ": " + value);
          }
          cEntity.addParm(gCode, value);
        }
        break;
      }
    }
    ArrayList<Shape> shapes = new ArrayList<>();
    for (DrawItem entity : entities) {
      if (doDraw(entity)) {
        Shape shape = entity.getShape();
        if (shape != null) {
          shapes.add(shape);
        }
      }
    }
    Shape[] sOut = new Shape[shapes.size()];
    if (shapes.size() > 0) {
      for (Shape shape : shapes) {
        bounds = bounds == null ? shape.getBounds2D() : bounds.createUnion(shape.getBounds2D());
      }
      double scale = 1;
      double maxAxis = Math.max(bounds.getWidth(), bounds.getHeight());
      // Limit size to maxSize inches on max dimension
      if (maxSize > 0 && maxAxis > maxSize) {
        scale = maxSize / maxAxis;
        scaled = true;
      }
      // If minSize specified, scale up max dimension to match
      if (minSize > 0 && maxAxis < minSize) {
        scale = minSize / maxAxis;
        scaled = true;
      }
      // Scale, as needed, and flip Y axis
      AffineTransform at = new AffineTransform();
      at.scale(scale, -scale);
      at.translate(-bounds.getMinX(), -bounds.getHeight() - bounds.getMinY());
      for (int ii = 0; ii < shapes.size(); ii++) {
        sOut[ii] = at.createTransformedShape(shapes.get(ii));
      }
    }
    return sOut;
  }

  /*
   * Simple DXF Viewer to test the Parser
   */

  static class DXFViewer extends JPanel implements Runnable {
    private DecimalFormat df = new DecimalFormat("#0.0#");
    private final double  SCREEN_PPI = Toolkit.getDefaultToolkit().getScreenResolution();
    private Shape[]       shapes;
    private double        border = 0.125;
    private DXFReader     dxf;
    private Rectangle2D   bounds;

    DXFViewer (String fileName, double maxSize, double minSize) throws IOException {
      dxf = new DXFReader();
      shapes = dxf.parseFile(new File(fileName), maxSize, minSize);
      if (shapes.length > 0) {
        // Create a bounding box that's the union of all shapes in the shapes array
        for (Shape shape : shapes) {
          bounds = bounds == null ? shape.getBounds2D() : bounds.createUnion(shape.getBounds2D());
        }
        if (bounds != null) {
          int wid = (int) Math.round((bounds.getWidth() + border * 2) * SCREEN_PPI);
          int hyt = (int) Math.round((bounds.getHeight() + border * 4) * SCREEN_PPI);   // Hmm.. why * 4 needed?
          setSize(new Dimension(Math.max(wid, 640), Math.max(hyt, 400)));
        }
        JFrame frame = new JFrame();
        frame.setTitle(fileName);
        frame.setSize(getSize());
        frame.add(this, BorderLayout.CENTER);
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setResizable(false);
        frame.setVisible(true);
        if (ANIMATE) {
          (new Thread(this)).start();
        }
      } else {
        throw new IllegalStateException("No shapes found in file: " + fileName);
      }
    }

    int frame = 0;

    public void run () {
      while (true) {
        try {
          Thread.sleep(500);
          frame++;
          repaint();
        } catch (InterruptedException ex) {
          ex.printStackTrace();
          return;
        }
      }
    }

    public void paint (Graphics g) {
      Dimension d = getSize();
      Graphics2D g2 = (Graphics2D) g;
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g2.setBackground(getBackground());
      g2.clearRect(0, 0, d.width, d.height);
      AffineTransform atScale = new AffineTransform();
      atScale.translate(border * SCREEN_PPI, border * SCREEN_PPI);
      atScale.scale(SCREEN_PPI, SCREEN_PPI);
      g2.setColor(Color.black);
      if (ANIMATE) {
        if (shapes != null) {
          int count = 0;
          for (Shape shape : shapes) {
            if (count++ >= frame) {
              break;
            }
            g2.draw(atScale.createTransformedShape(shape));
          }
        }
      } else {
        for (Shape shape : shapes) {
          g2.draw(atScale.createTransformedShape(shape));
        }
      }
      if (INFO) {
        int yOff = 30;
        g2.setFont(new Font("Monaco", Font.PLAIN, 12));
        g2.drawString("Paths:      " + shapes.length, 20, yOff);
        yOff += 15;
        g2.drawString("Original:   " + df.format(dxf.bounds.getWidth()) + " x " + df.format(dxf.bounds.getHeight()) + " inches", 20, yOff);
        yOff += 15;
        g2.drawString("Orig Units: " + dxf.units, 20, yOff);
        yOff += 15;
        if (dxf.scaled) {
          g2.drawString("Scaled To: " + df.format(bounds.getWidth()) + " x " + df.format(bounds.getHeight()) + " inches", 20, yOff);
        }
      }
    }
  }

  public static void main (String[] args) throws Exception {
    if (args.length < 1) {
      System.out.println("Usage: java -jar DXFReader.jar <dxf file>");
    } else {
      new DXFViewer(args[0], 14.0, 8.0);
    }
  }
}
