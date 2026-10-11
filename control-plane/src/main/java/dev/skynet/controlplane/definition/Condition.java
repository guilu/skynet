package dev.skynet.controlplane.definition;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Las condiciones de las fases ({@code condition: review.hasBlockingFindings}): un lenguaje pequeño
 * con la sintaxis de CEL. Admite rutas a datos ({@code tests.exitCode}, {@code inputs.foco}),
 * literales ({@code true}, {@code 3}, {@code 'main'}, {@code null}, {@code [1, 2]}), {@code == != <
 * <= > >=}, {@code && || !}, {@code in} y paréntesis. Sin llamadas ni acceso a nada más: el texto
 * viene de un YAML.
 *
 * <p>Un dato que falta (de una fase omitida) vale {@code null}, y {@code null} cuenta como falso.
 */
public final class Condition {

  private Condition() {}

  /** Una expresión; {@code position} es el índice de su primer carácter en el texto. */
  public sealed interface Expr permits Literal, Path, Not, Binary, ListOf {
    int position();
  }

  public record Literal(Object value, int position) implements Expr {}

  /** Una ruta a un dato, p. ej. {@code review.security.hasBlockingFindings}. */
  public record Path(List<String> parts, int position) implements Expr {

    public String text() {
      return String.join(".", parts);
    }
  }

  public record Not(Expr operand, int position) implements Expr {}

  /** {@code op}: {@code && || == != < <= > >= in}. */
  public record Binary(String op, Expr left, Expr right, int position) implements Expr {}

  public record ListOf(List<Expr> items, int position) implements Expr {}

  /** Un error en el texto de una condición, en el carácter {@code position}. */
  public static final class SyntaxError extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final int position;

    public SyntaxError(String message, int position) {
      super(message);
      this.position = position;
    }

    public int position() {
      return position;
    }
  }

  /** Un dato de un tipo que la condición no puede usar, al evaluarla. */
  public static final class EvaluationError extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public EvaluationError(String message) {
      super(message);
    }
  }

  /** Tipos que comprueba la validación. {@code ANY}: un campo de tipo array u object. */
  public enum Type {
    BOOLEAN,
    NUMBER,
    STRING,
    NULL,
    LIST,
    ANY;

    String label() {
      return switch (this) {
        case BOOLEAN -> "un booleano";
        case NUMBER -> "un número";
        case STRING -> "un texto";
        case NULL -> "null";
        case LIST -> "una lista";
        case ANY -> "un valor";
      };
    }
  }

  // ---- Lectura ----------------------------------------------------------------------------------

  public static Expr parse(String text) {
    Parser parser = new Parser(tokens(text), text.length());
    Expr expr = parser.or();
    Token next = parser.peek();
    if (next != null) {
      throw new SyntaxError("Sobra `" + next.text() + "`", next.position());
    }
    return expr;
  }

  private record Token(Kind kind, String text, int position) {}

  private enum Kind {
    NAME,
    NUMBER,
    STRING,
    SYMBOL
  }

  private static final List<String> SYMBOLS =
      List.of("&&", "||", "==", "!=", "<=", ">=", "<", ">", "!", "(", ")", "[", "]", ",", ".");

  private static List<Token> tokens(String text) {
    List<Token> tokens = new ArrayList<>();
    int i = 0;
    while (i < text.length()) {
      char c = text.charAt(i);
      if (Character.isWhitespace(c)) {
        i++;
      } else if (Character.isLetter(c) || c == '_') {
        int start = i;
        while (i < text.length() && isNamePart(text.charAt(i))) {
          i++;
        }
        tokens.add(new Token(Kind.NAME, text.substring(start, i), start));
      } else if (Character.isDigit(c)) {
        int start = i;
        while (i < text.length() && Character.isDigit(text.charAt(i))) {
          i++;
        }
        if (i + 1 < text.length()
            && text.charAt(i) == '.'
            && Character.isDigit(text.charAt(i + 1))) {
          i++;
          while (i < text.length() && Character.isDigit(text.charAt(i))) {
            i++;
          }
        }
        tokens.add(new Token(Kind.NUMBER, text.substring(start, i), start));
      } else if (c == '\'' || c == '"') {
        int start = i;
        int end = text.indexOf(c, i + 1);
        if (end < 0) {
          throw new SyntaxError("Falta la comilla que cierra el texto", start);
        }
        tokens.add(new Token(Kind.STRING, text.substring(start + 1, end), start));
        i = end + 1;
      } else {
        int at = i;
        String symbol =
            SYMBOLS.stream()
                .filter(s -> text.startsWith(s, at))
                .findFirst()
                .orElseThrow(() -> new SyntaxError("`" + c + "` no se entiende aquí", at));
        tokens.add(new Token(Kind.SYMBOL, symbol, i));
        i += symbol.length();
      }
    }
    return tokens;
  }

  /** Los ids de fase llevan guiones ({@code fix-review}): aquí no hay resta. */
  private static boolean isNamePart(char c) {
    return Character.isLetterOrDigit(c) || c == '_' || c == '-';
  }

  private static final class Parser {
    private final List<Token> tokens;
    private final int end;
    private int next;

    Parser(List<Token> tokens, int end) {
      this.tokens = tokens;
      this.end = end;
    }

    Token peek() {
      return next < tokens.size() ? tokens.get(next) : null;
    }

    boolean accept(String symbol) {
      Token token = peek();
      if (token != null && token.kind() != Kind.STRING && token.text().equals(symbol)) {
        next++;
        return true;
      }
      return false;
    }

    void expect(String symbol, String message) {
      if (!accept(symbol)) {
        Token token = peek();
        throw new SyntaxError(message, token == null ? end : token.position());
      }
    }

    Expr or() {
      Expr left = and();
      while (true) {
        Token token = peek();
        if (!accept("||")) {
          return left;
        }
        left = new Binary("||", left, and(), token.position());
      }
    }

    Expr and() {
      Expr left = unary();
      while (true) {
        Token token = peek();
        if (!accept("&&")) {
          return left;
        }
        left = new Binary("&&", left, unary(), token.position());
      }
    }

    Expr unary() {
      Token token = peek();
      if (accept("!")) {
        return new Not(unary(), token.position());
      }
      return comparison();
    }

    Expr comparison() {
      Expr left = primary();
      Token token = peek();
      for (String op : List.of("==", "!=", "<=", ">=", "<", ">", "in")) {
        if (token != null && token.kind() != Kind.STRING && token.text().equals(op)) {
          next++;
          return new Binary(op, left, primary(), token.position());
        }
      }
      return left;
    }

    Expr primary() {
      Token token = peek();
      if (token == null) {
        throw new SyntaxError("La condición termina antes de tiempo", end);
      }
      next++;
      switch (token.kind()) {
        case NUMBER -> {
          return new Literal(new BigDecimal(token.text()), token.position());
        }
        case STRING -> {
          return new Literal(token.text(), token.position());
        }
        case NAME -> {
          switch (token.text()) {
            case "true", "false" -> {
              return new Literal(Boolean.valueOf(token.text()), token.position());
            }
            case "null" -> {
              return new Literal(null, token.position());
            }
            case "in" -> throw new SyntaxError("Falta un dato antes de `in`", token.position());
            default -> {
              List<String> parts = new ArrayList<>(List.of(token.text()));
              while (accept(".")) {
                Token part = peek();
                if (part == null || part.kind() != Kind.NAME) {
                  throw new SyntaxError(
                      "Falta un nombre después del punto", part == null ? end : part.position());
                }
                next++;
                parts.add(part.text());
              }
              return new Path(List.copyOf(parts), token.position());
            }
          }
        }
        default -> {
          if (token.text().equals("(")) {
            Expr inner = or();
            expect(")", "Falta el `)` que cierra el paréntesis");
            return inner;
          }
          if (token.text().equals("[")) {
            List<Expr> items = new ArrayList<>();
            if (!accept("]")) {
              do {
                items.add(or());
              } while (accept(","));
              expect("]", "Falta el `]` que cierra la lista");
            }
            return new ListOf(List.copyOf(items), token.position());
          }
          throw new SyntaxError("`" + token.text() + "` no se entiende aquí", token.position());
        }
      }
    }
  }

  // ---- Validación -------------------------------------------------------------------------------

  /**
   * Comprueba los tipos de la condición, con el de cada ruta según {@code paths} (que lanza {@link
   * SyntaxError} si la ruta no vale). La condición entera tiene que ser booleana.
   */
  public static void check(Expr expr, Function<Path, Type> paths) {
    Type type = type(expr, paths);
    if (type != Type.BOOLEAN && type != Type.ANY && type != Type.NULL) {
      throw new SyntaxError(
          "La condición tiene que ser verdadera o falsa, y esto es " + type.label(),
          expr.position());
    }
  }

  private static Type type(Expr expr, Function<Path, Type> paths) {
    return switch (expr) {
      case Literal literal -> typeOf(literal.value());
      case Path path -> paths.apply(path);
      case ListOf list -> {
        list.items().forEach(i -> type(i, paths));
        yield Type.LIST;
      }
      case Not not -> {
        requireBoolean(not.operand(), type(not.operand(), paths), "!");
        yield Type.BOOLEAN;
      }
      case Binary binary -> {
        Type left = type(binary.left(), paths);
        Type right = type(binary.right(), paths);
        switch (binary.op()) {
          case "&&", "||" -> {
            requireBoolean(binary.left(), left, binary.op());
            requireBoolean(binary.right(), right, binary.op());
          }
          case "==", "!=" -> requireComparable(binary, left, right);
          case "in" -> {
            if (right != Type.LIST && right != Type.ANY) {
              throw new SyntaxError(
                  "Después de `in` va una lista, p. ej. `[1, 2]`", binary.right().position());
            }
            if (binary.right() instanceof ListOf list) {
              for (Expr item : list.items()) {
                requireComparable(binary, left, type(item, paths));
              }
            }
          }
          default -> {
            if (!(ordered(left) && ordered(right) && compatible(left, right))) {
              throw new SyntaxError(
                  "`"
                      + binary.op()
                      + "` compara dos números o dos textos, no "
                      + left.label()
                      + " y "
                      + right.label(),
                  binary.position());
            }
          }
        }
        yield Type.BOOLEAN;
      }
    };
  }

  private static void requireBoolean(Expr expr, Type type, String op) {
    if (type != Type.BOOLEAN && type != Type.ANY && type != Type.NULL) {
      throw new SyntaxError(
          "`" + op + "` necesita algo verdadero o falso, y esto es " + type.label(),
          expr.position());
    }
  }

  private static void requireComparable(Binary binary, Type left, Type right) {
    if (!compatible(left, right)) {
      throw new SyntaxError(
          "No se puede comparar " + left.label() + " con " + right.label(), binary.position());
    }
  }

  private static boolean ordered(Type type) {
    return type == Type.NUMBER || type == Type.STRING || type == Type.ANY || type == Type.NULL;
  }

  private static boolean compatible(Type left, Type right) {
    return left == right
        || left == Type.ANY
        || right == Type.ANY
        || left == Type.NULL
        || right == Type.NULL;
  }

  private static Type typeOf(Object value) {
    return switch (value) {
      case null -> Type.NULL;
      case Boolean b -> Type.BOOLEAN;
      case BigDecimal d -> Type.NUMBER;
      case String s -> Type.STRING;
      default -> Type.ANY;
    };
  }

  /** Las rutas que lee la condición, en orden y sin repetir. */
  public static List<Path> paths(Expr expr) {
    Map<String, Path> paths = new LinkedHashMap<>();
    collect(expr, paths);
    return List.copyOf(paths.values());
  }

  private static void collect(Expr expr, Map<String, Path> paths) {
    switch (expr) {
      case Path path -> paths.putIfAbsent(path.text(), path);
      case Not not -> collect(not.operand(), paths);
      case Binary binary -> {
        collect(binary.left(), paths);
        collect(binary.right(), paths);
      }
      case ListOf list -> list.items().forEach(i -> collect(i, paths));
      case Literal literal -> {
        // Sin datos.
      }
    }
  }

  // ---- Evaluación -------------------------------------------------------------------------------

  /**
   * Evalúa la condición con el valor de cada ruta ({@code null} si falta). Los números pueden
   * llegar como cualquier {@link Number}.
   */
  public static boolean test(Expr expr, Function<String, Object> values) {
    return truthy(expr, eval(expr, values));
  }

  private static Object eval(Expr expr, Function<String, Object> values) {
    return switch (expr) {
      case Literal literal -> literal.value();
      case Path path -> normalize(values.apply(path.text()));
      case ListOf list -> list.items().stream().map(i -> eval(i, values)).toList();
      case Not not -> !truthy(not.operand(), eval(not.operand(), values));
      case Binary binary ->
          switch (binary.op()) {
            case "&&" ->
                truthy(binary.left(), eval(binary.left(), values))
                    && truthy(binary.right(), eval(binary.right(), values));
            case "||" ->
                truthy(binary.left(), eval(binary.left(), values))
                    || truthy(binary.right(), eval(binary.right(), values));
            default -> compare(binary, eval(binary.left(), values), eval(binary.right(), values));
          };
    };
  }

  private static boolean compare(Binary binary, Object left, Object right) {
    switch (binary.op()) {
      case "==" -> {
        return same(left, right);
      }
      case "!=" -> {
        return !same(left, right);
      }
      case "in" -> {
        if (right == null) {
          return false;
        }
        if (!(right instanceof List<?> list)) {
          throw new EvaluationError("Después de `in` no hay una lista");
        }
        return list.stream().anyMatch(item -> same(left, normalize(item)));
      }
      default -> {
        if (left == null || right == null) {
          return false;
        }
        int order;
        if (left instanceof BigDecimal a && right instanceof BigDecimal b) {
          order = a.compareTo(b);
        } else if (left instanceof String a && right instanceof String b) {
          order = a.compareTo(b);
        } else {
          throw new EvaluationError(
              "`"
                  + binary.op()
                  + "` no puede comparar "
                  + describe(left)
                  + " y "
                  + describe(right));
        }
        return switch (binary.op()) {
          case "<" -> order < 0;
          case "<=" -> order <= 0;
          case ">" -> order > 0;
          default -> order >= 0;
        };
      }
    }
  }

  private static boolean same(Object left, Object right) {
    if (left instanceof BigDecimal a && right instanceof BigDecimal b) {
      return a.compareTo(b) == 0;
    }
    return Objects.equals(left, right);
  }

  private static boolean truthy(Expr expr, Object value) {
    return switch (value) {
      case null -> false;
      case Boolean b -> b;
      default ->
          throw new EvaluationError(
              "`"
                  + describe(expr)
                  + "` tendría que ser verdadero o falso, y vale "
                  + describe(value));
    };
  }

  private static Object normalize(Object value) {
    return value instanceof Number n && !(value instanceof BigDecimal)
        ? new BigDecimal(n.toString())
        : value;
  }

  private static String describe(Object value) {
    return switch (value) {
      case Path path -> path.text();
      case Expr e -> "la expresión";
      case String s -> "'" + s + "'";
      case null -> "null";
      default -> value.toString();
    };
  }
}
