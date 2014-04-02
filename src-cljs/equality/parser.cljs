(ns equality.parser
  (:use [equality.printing :only [print-expr mathml expr-str]])
  (:require [equality.geometry :as geom]
            [clojure.set]))

(set! cljs.core/*print-newline* false)

(set! cljs.core/*print-fn*
      (fn [& args]
        (.apply js/console.log js/console (into-array args))))

(derive :type/num :type/expr)
(derive :type/var :type/expr)
(derive :type/add :type/expr)
(derive :type/sub :type/expr)
(derive :type/mult :type/expr)
(derive :type/frac :type/expr)
(derive :type/pow :type/expr)
(derive :type/sqrt :type/expr)
(derive :type/bracket :type/expr)
;; NOTE: :type/eq is not an expr!

(defn precedence [type]
  (case type
    :type/symbol 999
    :type/num 999
    :type/var 999
    :type/add 5
    :type/sub 5
    :type/mult 10
    :type/eq 1
    :type/frac 15
    :type/pow 20
    :type/sqrt 999
    :type/bracket 999))


(defmulti symbols :type)

(defmethod symbols nil [expr]
  [])

(defmethod symbols :type/symbol [expr]
  [expr])

(defmethod symbols :type/num [expr]
  [expr])

(defmethod symbols :type/var [expr]
  [expr])

(defmethod symbols :type/add [expr]
  (concat [expr] (symbols (:left-op expr)) (symbols (:right-op expr))))

(defmethod symbols :type/sub [expr]
  (concat [expr] (symbols (:left-op expr)) (symbols (:right-op expr))))

(defmethod symbols :type/mult [expr]
  (concat (symbols (:left-op expr)) (symbols (:right-op expr)) (when (:id expr) [expr])))

(defmethod symbols :type/eq [expr]
  (concat [expr] (symbols (:left-op expr)) (symbols (:right-op expr))))

(defmethod symbols :type/frac [expr]
  (concat [expr] (symbols (:numerator expr)) (symbols (:denominator expr))))

(defmethod symbols :type/pow [expr]
  (concat (symbols (:base expr)) (symbols (:exponent expr))))

(defmethod symbols :type/sqrt [expr]
  (concat [expr] (symbols (:radicand expr))))

(defmethod symbols :type/bracket [expr]
  (concat [expr] (symbols (:child expr))))

(defn numeric? [str]
  (not (js/isNaN (js/parseFloat str))))

(defn binary-op-rule [token type]
  (fn [input]

    ;; Matching operators are those of type :type/symbol whose token is correct.

    (let [ops (filter #(and (isa? (:type %) :type/symbol)
                               (= (:token %) token)) input)
          result-sets-list (for [t ops
                   :let [remaining-input (disj input t)

                         ;; Potential left operands are expressions to the left of the operator
                         ;; with precedence greater than that of this operator.

                         potential-left-ops (filter #(and (geom/boxes-intersect? (geom/left-box t (* 2 (:width t)) (* 0.3 (:height t))) %)
                                                          (< (geom/bbox-right %) (:x (geom/bbox-middle t)))
                                                          (< (geom/bbox-right %) (+ (:left t) (* 0.5 (:width %))))
                                                          (isa? (:type %) :type/expr)
                                                          (> (precedence (:type %)) (precedence type))) remaining-input)

                         ;; Potential right operands are expressions to the right of the operator
                         ;; with precedence greater than that of this operator.

                         potential-right-ops (filter #(and (geom/boxes-intersect? (geom/right-box t (* 2 (:width t)) (* 0.3 (:height t))) %)
                                                           (> (:left %) (:x (geom/bbox-middle t)))
                                                           (> (:left %) (- (geom/bbox-right t) (* 0.5 (:width %))))
                                                           (isa? (:type %) :type/expr)
                                                           (>= (precedence (:type %)) (precedence type))) remaining-input)]
                   :when (and (not-empty potential-left-ops)
                              (not-empty potential-right-ops))]
               (for [left potential-left-ops
                     right potential-right-ops
                     :let [remaining-input (disj remaining-input left right)]]

                 ;; Now we have the left and right operands, create a new result set combining them with the appropriate operator.
                 (conj remaining-input (merge {:id (:id t)
                                               :type type
                                               :left-op left
                                               :right-op right
                                               :symbol-count (+ 1
                                                                (:symbol-count left)
                                                                (:symbol-count right))}
                                              (geom/bbox-combine left right t)))))]

          ;; Concatenate all the result sets into a final list.
          (apply concat result-sets-list))))

(def non-var-symbols #{"+" "-" "="})

;; Each rule has an :apply function, which takes a set of entities and returns a list of sets of entities, where
;; each element of the list is a transformation of the input set, hopefully with some entities combined into bigger ones.
(def rules
  {"num" {:apply (fn [input]
                   ;; This rule is unusual - it replaces all number symbols with :type/num expressions. No need to do one at a time.
                   (let [rtn (set (map (fn [potential-num]
                                         (if (and (isa? (:type potential-num) :type/symbol)
                                                  (numeric? (:token potential-num)))
                                           ;; Replace
                                           (merge potential-num {:type :type/num
                                                                 :symbol-count 1})
                                           ;; Do not replace
                                           potential-num)) input))]
                     (if (= rtn (set input))
                       []
                       [rtn])))}
   "var" {:apply (fn [input]
                   ;; This rule is unusual - it replaces all var symbols with :type/var expressions. No need to do one at a time.
                   (let [rtn (set (map (fn [potential-var]
                                         (if (and (isa? (:type potential-var) :type/symbol)
                                                  (not (numeric? (:token potential-var)))
                                                  (string? (:token potential-var))
                                                  (= (count (:token potential-var)) 1)
                                                  (not (contains? non-var-symbols (:token potential-var))))
                                           ;; Replace
                                           (merge potential-var {:type :type/var
                                                                 :symbol-count 1})
                                           ;; Do not replace
                                           potential-var)) input))]
                     (if (= rtn (set input))
                       []
                       [rtn])))}
   "horiz-line" {:apply (fn [input]
                          ;; Take a set of entities. Find the first :line and return a list of two sets of entities. One where the line has been replaced with subtract, one where it's been replaced with :frac
                          (let [line (first (filter #(and (isa? (:type %) :type/symbol)
                                                          (= (:token %) :line)) input))]
                            (if line
                              (let [remaining-input (disj input line)]
                                [(conj remaining-input (merge line {:token :frac
                                                                    :symbol-count 1}))
                                 (conj remaining-input (merge line {:token "-"
                                                                    :symbol-count 1}))])
                              [])))}

   "power" {:apply (fn [input]

                     ;; A potential base is any expression which has higher precedence than :type/pow

                     (let [potential-bases (filter #(and (isa? (:type %) :type/expr)
                                                         (> (precedence (:type %)) (precedence :type/pow))) input)]
                       (apply concat
                              (for [b potential-bases
                                    :let [remaining-input (disj input b)

                                          ;; A potential exponent is any expression
                                          ;; which is touched by a north-east line from the top-right corner of the base
                                          ;; and which does not extend below or left of the centre of the base

                                          potential-exponents (filter #(and (geom/line-intersects-box? {:x (geom/bbox-right b) :dx (:width b)
                                                                                                        :y (:top b) :dy (- (:width b))} %)
                                                                            (> (:left %) (+ (:left b) (* 0.5 (:width b))))
                                                                            (< (+ (:top %) (:height %)) (+ (:top b) (* 0.5 (:height b))))
                                                                            (isa? (:type %) :type/expr)) remaining-input)]
                                    :when (not-empty potential-exponents)]
                                (for [e potential-exponents
                                      :let [remaining-input (disj remaining-input e)]]

                                  ;; Now we have found b^e and removed b and e from our input.
                                  ;; Create a new :type/pow which refers to them, and add it to the set of results

                                  (conj remaining-input (merge {:type :type/pow
                                                                :base b
                                                                :exponent e
                                                                :symbol-count (+ (:symbol-count b)
                                                                                 (:symbol-count e))}
                                                               (geom/bbox-combine b e))))))))}
   "adjacent-mult" {:apply (fn [input]

                             ;; Potential coefficients are expressions that have higher precedence than :type/mult

                             (let [potential-left-ops (filter #(and (isa? (:type %) :type/expr)
                                                                    (> (precedence (:type %)) (precedence :type/mult))) input)
                                   result-sets-list (for [left potential-left-ops
                                                          :let [remaining-input (disj input left)

                                                                ;; Potential multiplicands are expressions to the right of "left"
                                                                ;; which have precedence >= :type/mult, which are not numbers.
                                                                ;; If they are of type :type/pow, the base must not be a number.
                                                                ;; If they are of type :type/mult, the left operand must not be a number.

                                                                potential-right-ops (filter #(and (geom/boxes-intersect? (geom/right-box left (* 1.5 (min (:width left) (:width %))) (* 0.3 (:height left))) %)
                                                                                                  (> (:left %) (- (geom/bbox-right left) (* 0.5 (:width %))))
                                                                                                  (> (:left %) (:x (geom/bbox-middle left)))
                                                                                                  (if (= (:type left) :type/pow)
                                                                                                    (> (geom/bbox-bottom %) (:y (geom/bbox-middle (:base left))))
                                                                                                    (> (geom/bbox-bottom %) (:y (geom/bbox-middle left))))
                                                                                                  (>= (precedence (:type %)) (precedence :type/mult))
                                                                                                  (not= (:type %) :type/num)
                                                                                                  (if (= (:type %) :type/pow)
                                                                                                    (not= (:type (:base %)) :type/num)
                                                                                                    true)
                                                                                                  (if (= (:type %) :type/mult)
                                                                                                    (not= (:type (:left-op %)) :type/num)
                                                                                                    true)
                                                                                                  (isa? (:type %) :type/expr)) remaining-input)]
                                                          :when (not-empty potential-right-ops)]
                                                      (for [right potential-right-ops
                                                            :let [remaining-input (disj remaining-input right)]]

                                                        ;; Now we have found coefficient and multiplicand and removed them from our input.
                                                        ;; Create a new :type/mult and add it to the set of results.

                                                        (conj remaining-input (merge {:type :type/mult
                                                                                      :left-op left
                                                                                      :right-op right
                                                                                      :symbol-count (+ (:symbol-count left)
                                                                                                       (:symbol-count right))}
                                                                                     (geom/bbox-combine left right)))))]

                               ;; result-sets-list contains a list with an element for every potential coefficient
                               ;; where each element is a list of new result sets, one for each potential multiplicand.
                               ;; Join these nested lists together into a final list of results.

                               (apply concat result-sets-list)))}

   "addition" {:apply (binary-op-rule "+" :type/add)}
   "subtraction" {:apply (binary-op-rule "-" :type/sub)}
   "equals" {:apply (binary-op-rule "=" :type/eq)}
   "fraction" {:apply (fn [input]
                        (let [frac-lines (filter #(and (isa? (:type %) :type/symbol)
                                                       (= (:token %) :frac)) input)
                              result-sets-list (for [t frac-lines
                                                     :let [remaining-input (disj input t)
                                                           ;; Numerators/denominators must be expressions above/below the fraction line
                                                           ;; that do not overhang the ends of the line by more than 10% of their width.

                                                           potential-numerators (filter #(and (isa? (:type %) :type/expr)
                                                                                              (geom/line-intersects-box? {:x (:left t)
                                                                                                                          :dx (:width t)
                                                                                                                          :y (- (:top t) (:height %))
                                                                                                                          :dy 0} %)
                                                                                              (> (:left %) (- (:left t) (* 0.1 (:width %))))
                                                                                              (< (geom/bbox-right %) (+ (geom/bbox-right t) (* 0.1 (:width %))))) remaining-input)
                                                           potential-denominators (filter #(and (isa? (:type %) :type/expr)
                                                                                                (geom/line-intersects-box? {:x (:left t)
                                                                                                                            :dx (:width t)
                                                                                                                            :y (+ (geom/bbox-bottom t) (:height %))
                                                                                                                            :dy 0} %)
                                                                                                (> (:left %) (- (:left t) (* 0.1 (:width %))))
                                                                                                (< (geom/bbox-right %) (+ (geom/bbox-right t) (* 0.1 (:width %))))) remaining-input)]
                                                     :when (and (= 1 (count potential-numerators))
                                                                (= 1 (count potential-denominators)))]
                                                 (for [numerator potential-numerators
                                                       denominator potential-denominators
                                                       :let [remaining-input (disj remaining-input numerator denominator)]]
                                                   (conj remaining-input (merge {:id (:id t)
                                                                                 :type :type/frac
                                                                                 :numerator numerator
                                                                                 :denominator denominator
                                                                                 :symbol-count (+ 1
                                                                                                  (:symbol-count numerator)
                                                                                                  (:symbol-count denominator))}
                                                                                (geom/bbox-combine t numerator denominator)))))]
                          (apply concat result-sets-list)))}
   "sqrt" {:apply (fn [input]
                    (let [radicals (filter #(and (isa? (:type %) :type/symbol)
                                                 (= (:token %) :sqrt)) input)
                          result-sets-list (for [r radicals
                                                 :let [remaining-input (disj input r)
                                                       potential-radicands (filter #(and (isa? (:type %) :type/expr)
                                                                                         (geom/box-contains-box r %)) remaining-input)]
                                                 :when (= 1 (count potential-radicands))]
                                             (for [radicand potential-radicands
                                                   :let [remaining-input (disj remaining-input radicand)]]
                                               (conj remaining-input (merge {:id (:id r)
                                                                             :type :type/sqrt
                                                                             :radicand radicand
                                                                             :symbol-count (+ 1 (:symbol-count radicand))}
                                                                            (geom/bbox-combine r radicand)))))]
                      (apply concat result-sets-list)))}
   "brackets" {:apply (fn [input]
                        (let [brackets (filter #(and (isa? (:type %) :type/symbol)
                                                     (= (:token %) :brackets)) input)
                              result-sets-list (for [b brackets
                                                     :let [remaining-input (disj input b)
                                                           potential-children (filter #(and (isa? (:type %) :type/expr)
                                                                                            (geom/box-contains-box b %)) remaining-input)]
                                                     :when (= 1 (count potential-children))]
                                                 (for [child potential-children
                                                       :let [remaining-input (disj remaining-input child)]]
                                                   (conj remaining-input (merge {:id (:id b)
                                                                                 :type :type/bracket
                                                                                 :child child
                                                                                 :symbol-count (+ 1 (:symbol-count child))}
                                                                                (geom/bbox-combine b child)))))]
                          (apply concat result-sets-list)))}})



(defn my-mem [f]
  (let [cache (atom {})]
    (fn [input]
      (if-let [k (some (fn [k] (if (clojure.set/subset? k input) k nil)) (keys @cache))]

        (do (println "Serving result from cache!" k ":::" (get @cache k)) (get @cache k))

        (let [result-sets (f input)
              symbols-relied-on (clojure.set/difference input (apply clojure.set/union result-sets))]
          (println "Going from" input "to" result-sets ", relied on" symbols-relied-on)
          (when (not-empty symbols-relied-on)
            (swap! cache #(assoc % symbols-relied-on result-sets)))
          result-sets)))))

(def mem-rules
  (apply merge (for [k (keys rules)]
                 {k {:apply (memoize (:apply (get rules k)))}})))
;; The parse function takes an input set of items, each of which might be a symbol,
;; expression or equation etc., and attempts to combine them using the rules defined above.
;; This process will produce many possible output sets of items, with rules applied (or not) in various orders.
;; Returns a list of output sets of items.

(declare parse)
(def parse
  (memoize
   (fn [input]

     ;; For every rule, apply it to the input list

     (let [rule-outputs (for [[k r] rules
                          :let [new-inputs ((:apply r) input)]
                          :when (not-empty new-inputs)]

                          ;; new-inputs is now a list of result-sets. Each result set is a
                          ;; transformation of the original input, hopefully with some
                          ;; items combined by this rule.

                          ;; Parse each of these new result sets, in the hope of
                          ;; reducing them even further.
                          (let [new-inputs-parsed (for [i new-inputs]
                                                    (parse i))]

                            ;; new-inputs-parsed has an element for each of the new result sets.
                            ;; Each element is a list of output sets, so join these elements together,
                            ;; returning a full list of output sets that we might end up with after
                            ;; applying this rule.
                            (apply concat new-inputs-parsed)))]

       ;; rule-outputs is a list, where each element is a list of output sets derived from applying some rule.
       ;; Join all these lists together into one full list of possible output sets from all rules.
       ;; Remove duplicates which have arisen from applying rules in different orders with identical results.
       ;; Add the original input to this set of valid parses, and return.
       (cons input (distinct (apply concat rule-outputs)))))))


(declare parse2)
(def parse2
  (fn [input]

    (let [one-app (apply concat [input] (for [[k r] mem-rules]
                                          ((:apply r) input)))]
      (sort-by count one-app))))


(defn parse5
  "Takes a list of sets and returns a list of sets"
  [input]
  (let [results (apply concat (map parse2 input))]
    (distinct results)))

(defn parse6 [input]
  (loop [last-sets nil
         sets (list input)]
    (let [sorted-sets (sort-by count sets)
          min-trees   (count (first sorted-sets))]
      (println "Sorted Sets (Best" min-trees "):" sorted-sets)
      (if (= sets last-sets)
        (take-while #(= min-trees (count %)) sorted-sets) ;; We have reached a fixed point.
        (if (= 1 min-trees)
          (take-while #(= 1 (count %)) sorted-sets) ;; We have found at least one parse that uses all symbols
          (recur sets (parse5 sorted-sets)))))))

(defn parse7 [input]
  (loop [i 0
         [head & rest :as full-input] [input]]
    (when head
      (when (= 1 (count head))
        (do
          (println "Found result after" i "passes. (Queue length" (count full-input) ")")
          (take-while #(= 1 (count %)) full-input)) ;; We have found at least one parse using all symbols
        )


      (let [head-results (apply concat (for [[k r] mem-rules] ((:apply r) head)))
            sorted-results (sort-by count head-results)]
      ;; (println "Head (" (count head) "items):" head)
      ;; (println "Sorted results:" sorted-results)
        (if (> i 300000)
          [head]
          (recur (inc i) (sort-by count (distinct (concat rest sorted-results)))))))))

(defn parse8 [input]
  (loop [i 0
         seen {}
         results #{}
         [head & rest :as full-input] [input]]
    (let [level (:level (meta head))
          parent (:parent (meta head))
          head-results (apply concat (for [[k r] mem-rules] (map #(with-meta % {:level (+ 1 level) :parent i}) ((:apply r) head))))
          head-results (filter (fn [result] (not (contains? seen result))) head-results)]

      (if (= 1 (count head))
        (print "RESULT on level" level ", set" i ":" (map expr-str head) ", parent:" parent)
        ;;(print "Level" level ", set" i ", queued:" (count full-input) ", head:" (count head) (interpose " | " (map expr-str head)) ", parent:" parent (if (empty? head-results) "BACKTRACKING" ""))
        )
      (if (and head (< (count results) 100))
        (recur (inc i)
               (apply (partial assoc seen) (apply concat (map (fn [%] [% true]) head-results)))
               (if (= 1 (count head))
                 (conj results (first head))
                 results)
               (sort-by count (distinct (concat rest head-results))))
        [results]))))


(defn to-clj-input [input]
  (set (map (fn [m] (apply merge (cons {:symbol-count 1} (map (fn [[k v]]
                                                               (cond (= :type k) {k (keyword v)}
                                                                     (and (= :token k)
                                                                          (string? v)
                                                                          (= (nth v 0) ":")) {k (keyword (.replace v ":" ""))}
                                                                     :else {k v})) m)))) (js->clj input :keywordize-keys true))))
(defn without-overlap [s]
  (set (filter #(not (:overlap %)) s)))


(defn map-with-meta [f m]
  (with-meta (map f m) (meta m)))

(defn compare-raw-symbol-count [a b]
  (let [symbol-count-a (count (filter #(= (:type %) :type/symbol) (apply concat (map symbols a))))
        symbol-count-b (count (filter #(= (:type %) :type/symbol) (apply concat (map symbols b))))]
    (cond
     (> symbol-count-a symbol-count-b) 1
     (< symbol-count-a symbol-count-b) -1
     :else 0)))

(defn get-best-results [input]
  (let [input       (to-clj-input input)
        all-symbols (set (map :id (flatten (map symbols input))))

        result      (time (parse8 input))

        _           (println "Result:" result)

        ;; Sort the results by the number of items left. Smaller is better (more combined). Then sort by number of raw symbols (not turned into var or num). Fewer is better.

        best-parses (sort (fn [a b]
                            (cond
                             (> (count a) (count b)) 1
                             (< (count a) (count b)) -1
                             :else (compare-raw-symbol-count a b))) result)

        best-result (first best-parses)

        ;; Within the best result, sort by number of symbols in combined items (more is better). Then sort by number of raw symbols (not turned into var or num). Fewer is better.

        formulae    (sort (fn [a b] (cond
                                    (> (:symbol-count a) (:symbol-count b)) -1
                                    (< (:symbol-count a) (:symbol-count b)) 1
                                    :else (let [symbol-count-a (count (filter #(= (:type %) :type/symbol) (symbols a)))
                                                symbol-count-b (count (filter #(= (:type %) :type/symbol) (symbols b)))]
                                            (cond
                                             (> symbol-count-a symbol-count-b) 1
                                             (< symbol-count-a symbol-count-b) -1
                                             :else 0)))) best-result)


        ;; The the formula with most symbols

        formula     (first formulae)


        unused-symbols (clojure.set/difference all-symbols (map :id (symbols formula)))

        ]

    (println "RESULT:" formula)

    (clj->js {:mathml  (mathml formula)
              :unusedSymbols unused-symbols})))

(set! (.-onmessage js/self) (fn [e]
                              (let [symbols (.-data.symbols e)]
                                (.postMessage js/self (get-best-results symbols)))))
