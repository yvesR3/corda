/* Copyright (c) 2008-2013, Avian Contributors

   Permission to use, copy, modify, and/or distribute this software
   for any purpose with or without fee is hereby granted, provided
   that the above copyright notice and this permission notice appear
   in all copies.

   There is NO WARRANTY for this software.  See license.txt for
   details. */

package regex;

import java.util.ArrayList;
import java.util.Stack;

/**
 * Compiles regular expressions into {@link PikeVM}s.
 * 
 * @author Johannes Schindelin
 */
class Compiler implements PikeVMOpcodes {
  private final static CharacterMatcher regularCharacter =
      CharacterMatcher.parse("[^\\\\.*+?|\\[\\]{}()^$]");

  private static class Output {
    private int[] program;
    private int offset;
    private int groupCount = -1;
    private int findPreambleSize;
    private ArrayList<CharacterMatcher> classes;
    private ArrayList<PikeVM> lookarounds;

    public Output(Expression expr) {
      // try-run to determine the code size
      expr.writeCode(this);
      program = new int[offset];
      offset = 0;
      groupCount = -1;
      classes = new ArrayList<CharacterMatcher>();
      lookarounds = new ArrayList<PikeVM>();
      // write it out!
      expr.writeCode(this);
    }

    public void add(int opcode) {
      if (program != null) {
        program[offset] = opcode;
      }
      offset++;
    }

    public int markJump() {
      return offset++;
    }

    public void setJump(int mark) {
      if (program != null) {
        program[mark] = offset;
      }
    }

    public void markFindPreambleEnd() {
      findPreambleSize = offset;
    }

    public PikeVM toVM() {
      CharacterMatcher[] classes = new CharacterMatcher[this.classes.size()];
      this.classes.toArray(classes);
      PikeVM[] lookarounds = new PikeVM[this.lookarounds.size()];
      this.lookarounds.toArray(lookarounds);
      return new PikeVM(program, findPreambleSize, groupCount, classes,
        lookarounds);
    }

    public int addClass(CharacterMatcher characterClass) {
      if (program == null) {
        return -1;
      }
      int result = classes.size();
      classes.add(characterClass);
      return result;
    }

    public int addLookaround(PikeVM lookaround) {
      if (program == null) {
        return -1;
      }
      int result = lookarounds.size();
      lookarounds.add(lookaround);
      return result;
    }
  }

  private abstract class Expression {
    protected abstract void writeCode(Output output);
  }

  private class CharacterRange extends Expression {
    private final CharacterMatcher characterClass;

    public CharacterRange(CharacterMatcher characterClass) {
      this.characterClass = characterClass;
    }

    protected void writeCode(Output output) {
      output.add(CHARACTER_CLASS);
      output.add(output.addClass(characterClass));
    }
  }

  private class Repeat extends Expression {
    private Expression expr;
    private int minCount, maxCount;
    private boolean greedy;

    public Repeat(Expression expr, int minCount, int maxCount, boolean greedy) {
      if (minCount != 0 && minCount != 1) {
        throw new RuntimeException("Unexpected min count: " + minCount);
      }
      if (maxCount != 1 && maxCount != -1) {
        throw new RuntimeException("Unexpected max count: " + maxCount);
      }
      this.expr = expr;
      this.minCount = minCount;
      this.maxCount = maxCount;
      this.greedy = greedy;
    }

    protected void writeCode(Output output) {
      int start = output.offset;
      int splitJmp = greedy ? SPLIT_JMP : SPLIT;
      int split = greedy ? SPLIT : SPLIT_JMP;
      if (minCount == 1 && maxCount == -1) {
        expr.writeCode(output);
        output.add(splitJmp);
        output.add(start);
      } else if (minCount == 0 && maxCount == -1) {
        output.add(split);
        int jump = output.markJump();
        expr.writeCode(output);
        output.add(splitJmp);
        output.add(start + 2);
        output.setJump(jump);
      } else if (minCount == 0 && maxCount == 1) {
        output.add(split);
        int jump = output.markJump();
        expr.writeCode(output);
        output.setJump(jump);
      } else {
        throw new RuntimeException("Unexpected range: "
          + minCount + ", " + maxCount);
      }
    }
  }

  private class Group extends Expression {
    private final boolean capturing;

    private ArrayList<Expression> list = new ArrayList<Expression>();
    private ArrayList<Group> alternatives;

    public Group(boolean capturing, ArrayList<Expression> initialList) {
      this.capturing = capturing;
      if (initialList != null) {
        list.addAll(initialList);
      }
    }

    public void push(Expression expr) {
      list.add(expr);
    }

    public void push(final int c) {
      push(new Expression() {
        public void writeCode(Output output) {
          output.add(c);
        }
      });
    }

    public void startAlternative() {
      if (alternatives == null) {
        alternatives = new ArrayList<Group>();
      }
      alternatives.add(new Group(false, list));
      list.clear();
    }

    public Expression pop() {
      Expression result = list.remove(list.size() - 1);
      return result;
    }

    protected void writeCode(Output output) {
      int groupIndex = -1;
      if (capturing) {
        groupIndex = ++ output.groupCount;
        output.add(SAVE_OFFSET);
        output.add(2 * groupIndex);
      }
      int[] jumps = null;
      if (alternatives != null) {
        jumps = new int[alternatives.size()];
        int i = 0;
        for (Group alternative : alternatives) {
          output.add(SPLIT);
          int jump = output.markJump();
          alternative.writeCode(output);
          output.add(JMP);
          jumps[i++] = output.markJump();
          output.setJump(jump);
        }
      }
      for (Expression expr : list) {
        expr.writeCode(output);
      }
      if (jumps != null) {
        for (int jump : jumps) {
          output.setJump(jump);
        }
      }
      if (capturing) {
        output.add(SAVE_OFFSET);
        output.add(2 * groupIndex + 1);
      }
    }
  }

  private class Lookaround extends Expression {
    private final Group group = new Group(false, null);
    private final boolean forward, negative;

    public Lookaround(boolean forward, boolean negative) {
      this.forward = forward;
      this.negative = negative;
    }

    @Override
    protected void writeCode(Output output) {
      PikeVM vm = new Output(group).toVM();
      if (!forward) {
        vm.reverse();
      }
      output.add(forward ?
        (negative ? NEGATIVE_LOOKAHEAD : LOOKAHEAD) :
        (negative ? NEGATIVE_LOOKAHEAD : LOOKBEHIND));
      output.add(output.addLookaround(vm));
    }
  }

  private class Group0 extends Expression {
    private final Group group;

    public Group0() {
      group = new Group(true, null);
    }

    public void writeCode(Output output) {
      // find() preamble
      int start = output.offset;
      output.add(SPLIT_JMP);
      output.add(start + 5);
      output.add(DOTALL);
      output.add(SPLIT);
      output.add(start + 2);
      output.markFindPreambleEnd();
      group.writeCode(output);
    }
  }

  private Group0 root;
  private Stack<Group> groups;

  public Compiler() {
    root = new Group0();
    groups = new Stack<Group>();
    groups.add(root.group);
  }

  public Pattern compile(String regex) {
    char[] array = regex.toCharArray();
    CharacterMatcher.Parser characterClassParser =
      new CharacterMatcher.Parser(array);
    for (int index = 0; index < array.length; ++ index) {
      char c = array[index];
      Group current = groups.peek();
      if (regularCharacter.matches(c)) {
        current.push(c);
        continue;
      }
      switch (c) {
      case '.':
        current.push(DOT);
        continue;
      case '\\':
        int unescaped = characterClassParser.parseEscapedCharacter(index + 1);
        if (unescaped >= 0) {
          index = characterClassParser.getEndOffset() - 1;
          current.push((char)unescaped);
          continue;
        }
        CharacterMatcher characterClass = characterClassParser.parseClass(index);
        if (characterClass != null) {
          index = characterClassParser.getEndOffset() - 1;
          current.push(new CharacterRange(characterClass));
          continue;
        }
        switch (array[index + 1]) {
        case 'b':
          index++;
          current.push(WORD_BOUNDARY);
          continue;
        case 'B':
          index++;
          current.push(NON_WORD_BOUNDARY);
          continue;
        }
        throw new RuntimeException("Parse error @" + index + ": " + regex);
      case '?':
      case '*':
      case '+': {
        boolean greedy = true;
        if (index + 1 < array.length && array[index + 1] == '?') {
          greedy = false;
          ++ index;
        }
        current.push(new Repeat(current.pop(),
          c == '+' ? 1 : 0, c == '?' ? 1 : -1, greedy));
        continue;
      }
      case '(': {
        boolean capturing = true;
        if (index + 1 < array.length && array[index + 1] == '?') {
          index += 2;
          if (index >= array.length) {
            throw new RuntimeException("Short pattern @" + index + ": "
              + regex);
          }
          c = array[index];
          boolean lookAhead = true;
          if (c == '<') {
            if (++ index >= array.length) {
              throw new RuntimeException("Short pattern @" + index + ": "
                + regex);
            }
            lookAhead = false;
            c = array[index];
            if (c != '=' && c != '!') {
              throw new IllegalArgumentException("Named groups not supported @"
                + index + ": " + regex);
            }
          }
          switch (c) {
          case ':':
            capturing = false;
            break;
          case '!':
          case '=': {
            capturing = false;
            Lookaround lookaround = new Lookaround(lookAhead, c == '!');
            current.push(lookaround);
            groups.push(lookaround.group);
            continue;
          }
          default:
            throw new UnsupportedOperationException("Not yet supported: "
              + regex.substring(index));
          }
        }
        current.push(groups.push(new Group(capturing, null)));
        continue;
      }
      case ')':
        if (groups.size() < 2) {
          throw new RuntimeException("Invalid group close @" + index + ": "
            + regex);
        }
        groups.pop();
        continue;
      case '[': {
        CharacterMatcher matcher = characterClassParser.parseClass(index);
        if (matcher == null) {
          throw new RuntimeException("Invalid range @" + index + ": " + regex);
        }
        current.push(new CharacterRange(matcher));
        index = characterClassParser.getEndOffset() - 1;
        continue;
      }
      case '|':
        current.startAlternative();
        continue;
      case '^':
        current.push(LINE_START);
        continue;
      case '$':
        current.push(LINE_END);
        continue;
      default:
        throw new RuntimeException("Parse error @" + index + ": " + regex);
      }
    }
    if (groups.size() != 1) {
      throw new IllegalArgumentException("Unclosed groups: ("
        + (groups.size() - 1) + "): " + regex);
    }
    PikeVM vm = new Output(root).toVM();
    String plain = vm.isPlainString();
    if (plain != null) {
      return new TrivialPattern(regex, plain, 0);
    }
    return new RegexPattern(regex, 0, vm);
  }
}
