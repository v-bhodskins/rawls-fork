/*
 * This is a modified version of the official JSON grammar, supporting the proposed extensions. All
 * other ExtendedJSON* files are generated by antlr from this one. Antlr supports both JS and Java
 * as target languages.
 *
 * The rule `root` is needed to not allow expressions with unbalanced quotes. So something like
 * ""str" is invalid.
 */

grammar ExtendedJSON;

root: value EOF;

obj: '{' pair (',' pair)* '}' | '{' '}';

pair: STRING ':' value;

arr: '[' value (',' value)* ']' | '[' ']';

lookup: SYMBOL ('.' SYMBOL | ':' SYMBOL)+;

value: literal | obj | arr | lookup;

literal: STRING | NUMBER | 'true' | 'false' | 'null';

STRING: '"' (ESC | SAFECODEPOINT)* '"';

fragment ESC: '\\' (["\\/bfnrt] | UNICODE);

fragment UNICODE: 'u' HEX HEX HEX HEX;

fragment HEX: [0-9a-fA-F];

fragment SAFECODEPOINT: ~ ["\\\u0000-\u001F];

NUMBER: '-'? INT ('.' [0-9] +)? EXP?;

fragment INT: '0' | [1-9] [0-9]*;

fragment EXP: [Ee] [+\-]? INT;

SYMBOL: [a-zA-Z0-9_]+;

WS: [ \t\n\r] + -> skip;
