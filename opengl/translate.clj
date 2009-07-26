;   Copyright (c) Zachary Tellman. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns penumbra.opengl.translate
  (:use [clojure.contrib.def :only (defmacro-)])
  (:use [clojure.contrib.pprint])
  (:use [penumbra.opengl.core])
  (:require [clojure.zip :as zip]))

;;;;;;;;;;;;;;;;;;;

(defmulti glsl-macro
  #(if (seq? %) (first %) nil)
  :default :none)

(defmulti glsl-generator
  #(if (seq? %) (first %) nil)
  :default :none)

(declare special-parse-case?)

(defmulti glsl-parser
  #(if (special-parse-case? %) nil (first %))
  :default :function)

;;;;;;;;;;;;;;;;;;;;

(defn- transform
  "Maps glsl-macro over the entire source tree."
  [expr]
  (if (empty? expr)
    ()
    (loop [z (zip/seq-zip expr)]
      (if (zip/end? z)
        (zip/root z)
        (recur (zip/next (zip/replace z (glsl-macro (zip/node z)))))))))

(defn- generate [expr]
  (loop [body (list expr) tail (transform (glsl-generator expr))]
    (if (empty? tail)
     body
     (recur
       (concat body tail)
       (transform (glsl-generator tail))))))

(defn- parse-lines
  "Maps glsl-parser over a list of s-expressions, filters out empty lines,
  and optionally adds a terminating character."
  ([exprs] (parse-lines "" exprs))
  ([termination exprs]
    (if (and (seq? exprs) (= 1 (count exprs)) (seq? (first exprs)))
      (parse-lines termination (first exprs))
      (let [exprs (if (seq? (first exprs)) exprs (list exprs))
            parsed-exprs (map #(glsl-parser %) exprs)
            filtered-exprs (filter #(not= (.trim %) "") parsed-exprs)
            terminated-exprs (map #(if (.endsWith % "\n") % (str % termination "\n")) filtered-exprs)]
        (apply str terminated-exprs)))))

(defn- indent
  "Indents every line two spaces."
  [s]
  (let [lines (seq (.split s "\n"))]
    (str (apply str (interpose "\n" (map #(str "  " %) lines))) "\n")))

(defn translate-shader
  [decl exprs]
  (let [parsed-decl (if (empty? decl) "" (parse-lines ";" (map #(list 'declare %) decl)))
        body        (list 'main exprs)
        transformed (transform body)
        generated   (reverse (generate transformed))]
    (str parsed-decl (parse-lines generated))))

;;;;;;;;;;;;;;;;;;;;
;shader macros

(defmethod glsl-macro :none [expr]
  expr)

(defmethod glsl-macro 'let
  [expr]
  (concat
    '(do)
    (map #(list 'set! (first %) (second %)) (partition 2 (second expr)))
    (nnext expr)))

(defn- combine [term expr]
  (if (seq? expr)
    `(~(first expr) ~term ~@(rest expr))
    `(~expr ~term)))

(defmethod glsl-macro '->
  [expr]
  (let [term  (second expr)
        expr  (nnext expr)]
    (reduce combine term expr)))

;;;;;;;;;;;;;;;;;;;;
;shader generators

(defmethod glsl-generator :none [expr]
  (if (seq? expr)
    (apply concat (map glsl-generator expr))
    nil))

(defmethod glsl-generator 'import [expr]
  (apply concat
    (map
      (fn [lst]
        (let [namespace (first lst)]
          (map #(var-get (intern namespace %)) (next lst))))
    (next expr))))

(defmethod glsl-parser 'import [expr] "")

;;;;;;;;;;;;;;;;;;;;
;shader parser

(defn- swizzle?
  "Any function starting with a '.' is treated as a member access.
  (.xyz position) -> position.xyz"
  [expr]
  (and (seq? expr) (= \. (-> expr first name first))))

(defn- third [expr] (-> expr next second))
(defn- fourth [expr] (-> expr next next second))

(defn parse-keyword
  "Turns :model-view-matrix into gl_ModelViewMatrix."
  [k]
  (str
    "gl_"
    (apply str
      (map
        #(str (.. % (substring 0 1) (toUpperCase)) (. % substring 1 (count %)))
        (seq (.split (name k) "-"))))))

(defn parse-assignment
  "Parses the l-value in an assignment expression."
  [expr]
  (cond
    (keyword? expr)   (parse-keyword expr)
    (swizzle? expr)   (str (apply str (interpose " " (map name (next expr)))) (-> expr first name))
    (symbol? expr)    (.replace (name expr) \- \_)
    (empty? expr)     ""
    :else             (apply str (interpose " " (map parse-assignment expr)))))

(defn- special-parse-case? [expr]
  (or
    (swizzle? expr)
    (not (seq? expr))))

(defmethod glsl-parser nil
  ;handle base cases
  [expr]
  (cond
    (keyword? expr)           (parse-keyword expr)
    (swizzle? expr)           (str (-> expr second glsl-parser) (-> expr first str))
    (not (seq? expr))  (.replace (str expr) \- \_)
    :else                     ""))

(defmethod glsl-parser :function
  ;transforms (a b c d) into a(b, c, d)
  [expr]
  (str
    (.replace (name (first expr)) \- \_)
    "(" (apply str (interpose ", " (map glsl-parser (next expr)))) ")"))

(defn- concat-operators
  "Interposes operators between two or more operands, enforcing left-to-right evaluation.
  (- a b c) -> ((a - b) - c)"
  [op expr]
  (if (= 2 (count expr))
    (str "(" (glsl-parser (second expr)) " " op " " (glsl-parser (first expr)) ")")
    (str "(" (concat-operators op (rest expr)) " " op " " (glsl-parser (first expr)) ")")))

(defmacro def-infix-parser
  "Defines an infix operator
  (+ a b) -> a + b"
  [op-symbol op-string]
  `(defmethod glsl-parser ~op-symbol [expr#]
    (concat-operators ~op-string (reverse (next expr#)))))

(defmacro def-unary-parser
  "Defines a unary operator
  (not a) -> !a"
  [op-symbol op-string]
  `(defmethod glsl-parser ~op-symbol [expr#]
    (str ~op-string (glsl-parser (second expr#)))))

(defmacro def-assignment-parser
  "Defines an assignment operator, making use of parse-assignment for the l-value
  (set a b) -> a = b"
  [op-symbol op-string]
  `(defmethod glsl-parser ~op-symbol [expr#]
    (if (= 2 (count expr#))
      (str (parse-assignment (second expr#)))
      (str (parse-assignment (second expr#)) " " ~op-string " " (glsl-parser (third expr#))))))

(defmacro def-scope-parser
  "Defines a wrapper for any keyword that wraps a scope
  (if a b) -> if (a) { b }"
  [scope-symbol scope-fn]
  `(defmethod glsl-parser ~scope-symbol [expr#]
    (let [[header# body#] (~scope-fn expr#)]
      (str header# "\n{\n" (indent (parse-lines ";" body#)) "}\n"))))

(def-infix-parser '+ "+")
(def-infix-parser '/ "/")
(def-infix-parser '* "*")
(def-infix-parser '= "==")
(def-infix-parser 'and "&&")
(def-infix-parser 'or "||")
(def-infix-parser 'xor "^^")
(def-infix-parser '< "<")
(def-infix-parser '<= "<=")
(def-infix-parser '> ">")
(def-infix-parser '>= ">=")
(def-unary-parser 'not "!")
(def-unary-parser '++ "++")
(def-unary-parser '-- "--")
(def-assignment-parser 'declare "")
(def-assignment-parser 'set! "=")
(def-assignment-parser '+= "+=")
(def-assignment-parser '-= "-=")
(def-assignment-parser '*= "*=")

(defmethod glsl-parser '-
  ;the '-' symbol can either be a infix or unary operator
  [expr]
  (if (>= 2 (count expr))
    (str "-" (glsl-parser (second expr)))
    (concat-operators "-" (reverse (next expr)))))

(defmethod glsl-parser 'nth
  [expr]
  (str (glsl-parser (second expr)) "[" (third expr) "]"))

(defn- main-header [expr] ["void main()" (next expr)])
(def-scope-parser 'main main-header)

(defmethod glsl-parser 'do
  [expr]
  (parse-lines ";" (next expr)))

(defmethod glsl-parser 'if
  [expr]
  (str
    "if (" (glsl-parser (second expr)) ")"
    "\n{\n" (indent (parse-lines ";" (third expr))) "}\n"
    (if (< 3 (count expr))
      (str "else\n{\n" (indent (parse-lines ";" (fourth expr))) "}\n")
      "")))

(defmethod glsl-parser 'return
  [expr]
  (str "return " (glsl-parser (second expr))))

(defn- fn-header [expr]
  [(str
     (second expr) " " (.replace (name (third expr)) \- \_)
     "(" (apply str (interpose ", " (map parse-assignment (fourth expr)))) ")")
   (drop 4 expr)])
(def-scope-parser 'defn fn-header)