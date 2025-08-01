% This is generated by ESQL's AbstractFunctionTestCase. Do not edit it. See ../README.md for how to regenerate it.

Matching special characters (eg. `.`, `*`, `(`...) will require escaping.
The escape character is backslash `\`. Since also backslash is a special character in string literals,
it will require further escaping.

```esql
ROW message = "foo ( bar"
| WHERE message RLIKE "foo \\( bar"
```


To reduce the overhead of escaping, we suggest using triple quotes strings `"""`

```esql
ROW message = "foo ( bar"
| WHERE message RLIKE """foo \( bar"""
```

```{applies_to}
stack: ga 9.2
serverless: ga
```

Both a single pattern or a list of patterns are supported. If a list of patterns is provided,
the expression will return true if any of the patterns match.

```esql
ROW message = "foobar"
| WHERE message RLIKE ("foo.*", "bar.")
```


