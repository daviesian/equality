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
  [(symbols (:src expr))])

(defmethod symbols :type/var [expr]
  [(symbols (:src expr))])

(defmethod symbols :type/add [expr]
  (concat [(symbols (:src expr))] (symbols (:left-op expr)) (symbols (:right-op expr))))

(defmethod symbols :type/sub [expr]
  (concat [(symbols (:src expr))] (symbols (:left-op expr)) (symbols (:right-op expr))))

(defmethod symbols :type/mult [expr]
  (concat (symbols (:left-op expr)) (symbols (:right-op expr)) (when (:id expr) [(symbols (:src expr))])))

(defmethod symbols :type/eq [expr]
  (concat [(symbols (:src expr))] (symbols (:left-op expr)) (symbols (:right-op expr))))

(defmethod symbols :type/frac [expr]
  (concat [(symbols (:src expr))] (symbols (:numerator expr)) (symbols (:denominator expr))))

(defmethod symbols :type/pow [expr]
  (concat (symbols (:base expr)) (symbols (:exponent expr))))

(defmethod symbols :type/sqrt [expr]
  (concat [(symbols (:src expr))] (symbols (:radicand expr))))

(defmethod symbols :type/bracket [expr]
  (concat [(symbols (:src expr))] (symbols (:child expr))))

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
                                               :src t
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
                                           (with-meta (merge potential-num {:type :type/num
                                                                            :src potential-num
                                                                            :symbol-count 1}) {:certain true})
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
                                           (with-meta (merge potential-var {:type :type/var
                                                                            :src potential-var
                                                                            :symbol-count 1}) {:certain true})
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
                                                                    :src line
                                                                    :symbol-count 1}))
                                 (conj remaining-input (merge line {:token "-"
                                                                    :src line
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
                                                                                                  #_(if (= (:type left) :type/pow)
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
                                                                                 :src t
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
                                               (conj remaining-input (with-meta (merge {:id (:id r)
                                                                                        :type :type/sqrt
                                                                                        :src r
                                                                                        :radicand radicand
                                                                                        :symbol-count (+ 1 (:symbol-count radicand))}
                                                                                       (geom/bbox-combine r radicand)) {:certain true}))))]
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
                                                   (conj remaining-input (with-meta (merge {:id (:id b)
                                                                                            :type :type/bracket
                                                                                            :src b
                                                                                            :child child
                                                                                            :symbol-count (+ 1 (:symbol-count child))}
                                                                                           (geom/bbox-combine b child)) {:certain true}))))]
                          (apply concat result-sets-list)))}})

(defn parse [input]
  (loop [i 0
         j 0
         seen {}
         results #{}
         certain #{}
         [head & rest :as full-input] [input]]
    (let [level (:level (meta head))
          parent (:parent (meta head))
          head-results (apply concat (for [[k r] rules] (map #(with-meta % {:level (+ 1 level) :parent i}) ((:apply r) head))))
          head-results (filter (fn [result] (not (contains? seen result))) head-results)

          new-certain-symbols (clojure.set/difference (set (apply concat (map (fn [result] (filter (fn [sym] (:certain (meta sym))) result)) head-results))) certain)
          first-new-certain (first new-certain-symbols)

          new-certain (conj certain first-new-certain)

          head-results (if first-new-certain
                         (filter (fn [result] (every? #(contains? result %) new-certain)) head-results)
                         head-results)

          ]

      (if (= 1 (count head))
        (println "RESULT on level" level ", set" i ":" (map expr-str head) ", parent:" parent)
        (print "Level" level ", set" i ", queued:" (count full-input) ", head:" (count head) (interpose " | " (map expr-str head)) ", parent:" parent (if (empty? head-results) "BACKTRACKING" ""))
        )

      (when first-new-certain
        (println "New certain expr:" first-new-certain))
      (if (and head (< (count results) 100))
        (recur (inc i)
               j
               (apply (partial assoc seen) (apply concat (map (fn [%] [% true]) head-results)))
               (if (= 1 (count head))
                 (conj results (first head))
                 results)
               new-certain
               (sort-by count (distinct (concat rest head-results))))
        (do
          (println "Finished searching" i "sets.")
          ;;(println (contains? #{"a" "b"} nil))
          results)))))


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

        result      [(time (parse input))]

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


        unused-symbols (clojure.set/difference all-symbols (map :id (symbols formula)))]

    (println "RESULT:" formula)

    (clj->js {:mathml  (mathml formula)
              :unusedSymbols unused-symbols})))

(set! (.-onmessage js/self) (fn [e]
                              (let [symbols (.-data.symbols e)]
                                (.postMessage js/self (get-best-results symbols)))))
