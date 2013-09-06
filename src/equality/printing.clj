(ns equality.printing
  (:use [clojure.pprint]))

(defmulti expr-str :type)

(defmethod expr-str :default [expr]
  (str (:token expr)))

(defmethod expr-str :type/pow [expr]
  (str (expr-str (:base expr)) "^(" (expr-str (:exponent expr)) ")"))

(defmethod expr-str :type/add [expr]
  (str (expr-str (:left-op expr)) " + " (expr-str (:right-op expr))))

(defmethod expr-str :type/sub [expr]
  (str (expr-str (:left-op expr)) " - " (expr-str (:right-op expr))))

(defmethod expr-str :type/mult [expr]
  (str "(" (expr-str (:left-op expr)) (expr-str (:right-op expr)) ")"))

(defmethod expr-str :type/eq [expr]
  (str (expr-str (:left-op expr)) " = " (expr-str (:right-op expr))))

(defmethod expr-str :type/frac [expr]
  (str "(" (expr-str (:numerator expr)) ") / (" (expr-str (:denominator expr)) ")"))

(defn print-expr [expr]
  (pprint (expr-str expr)))

(defmulti mathml-inner :type)

(defmethod mathml-inner :type/var [expr]
  (str "<mi>" (:token expr) "</mi>"))

(defmethod mathml-inner :type/num [expr]
  (str "<mn>" (:token expr) "</mn>"))

(defmethod mathml-inner :type/pow [expr]
  (str "<mrow><msup>" (mathml-inner (:base expr)) (mathml-inner (:exponent expr)) "</msup></mrow>"))

(defmethod mathml-inner :type/add [expr]
  (str "<mrow>" (mathml-inner (:left-op expr)) "<mo>+</mo>" (mathml-inner (:right-op expr)) "</mrow>"))

(defmethod mathml-inner :type/sub [expr]
  (str "<mrow>" (mathml-inner (:left-op expr)) "<mo>-</mo>" (mathml-inner (:right-op expr)) "</mrow>"))

(defmethod mathml-inner :type/mult [expr]
  (str "<mrow>" (mathml-inner (:left-op expr)) (mathml-inner (:right-op expr)) "</mrow>"))

(defmethod mathml-inner :type/eq [expr]
  (str "<mrow>" (mathml-inner (:left-op expr)) "<mo>=</mo>" (mathml-inner (:right-op expr)) "</mrow>"))

(defmethod mathml-inner :type/frac [expr]
  (str "<mfrac><mrow>" (mathml-inner (:numerator expr)) "</mrow><mrow>" (mathml-inner (:denominator expr)) "</mrow></mfrac>"))

(defn mathml [expr]
  (str "<math display=\"block\"><mrow>" (mathml-inner expr) "</mrow></math>"))
