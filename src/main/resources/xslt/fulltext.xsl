<?xml version="1.0" encoding="UTF-8"?>
<!--
  fulltext.xsl — judgment XML to the plain-text artifact an embedding or search pipeline reads.

  Single purpose on purpose: no markup, no metadata, no structure. Every paragraph in document
  order, each collapsed to single spaces, joined by exactly one space. The document order of
  lex:p across all sections IS the reading order of the judgment, so no sorting is needed or
  wanted — a judgment reads facts, then reasons, then disposition.
-->
<xsl:stylesheet version="3.0"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:lex="urn:lex:content:1"
                exclude-result-prefixes="lex">

  <xsl:output method="text" encoding="UTF-8"/>

  <xsl:template match="/">
    <!--
      "!" applies normalize-space to each paragraph in turn; separator=" " then joins the
      resulting strings. Doing it in that order matters: normalizing first means a paragraph that
      is pretty-printed across three source lines contributes one clean run of text, and the
      single space between paragraphs stays the only paragraph boundary.
    -->
    <xsl:value-of select="lex:judgment/lex:body/lex:section/lex:p ! normalize-space(.)"
                  separator=" "/>
  </xsl:template>

</xsl:stylesheet>
