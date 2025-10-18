package me.anno.zauberei.typeresolution.complex

class Solver {
   /* var nextVarId = 0
    val typeBindings = mutableMapOf<Int, Type>() // when Var -> Concrete type
    val constraints = ArrayList<Constraint>()

    // reverse index: TypeVar id -> list of constraints that mention it
    val watchers = mutableMapOf<Int, MutableList<Constraint>>()

    val worklist: ArrayDeque<Constraint> = ArrayDeque()

    fun newVar(): ResolvedType.Var = ResolvedType.Var(nextVarId++)

    // add constraint & possibly push to worklist
    fun addConstraint(c: Constraint) {
        constraints += c
        worklist.addLast(c)
        // index watchers for vars appearing in c
        for (v in varsIn(c)) watchers.getOrPut(v) { mutableListOf() }.add(c)
    }

    // main loop
    fun solve() {
        while (worklist.isNotEmpty()) {
            val c = worklist.removeFirst()
            if (tryReduce(c)) {
                // if reduced to progress, re-enqueue watchers of changed var(s)
                for (changedVar in recentlyBoundVars()) {
                    watchers[changedVar]?.forEach { worklist.addLast(it) }
                }
            }
        }
    }

    // attempt to apply / reduce a constraint; returns true if it made progress
    fun tryReduce(c: Constraint): Boolean {
        ...
    }

    fun reduceMemberAccess(ma: MemberAccess): Boolean {
        val baseResolved = resolve(ma.base) // Either concrete Type or unresolved Var
        if (baseResolved is ResolvedType.Var) return false // wait
        val baseType = (baseResolved as ResolvedType.Concrete).t

        // Collect candidate members in baseType (including extensions, companion, imports):
        val candidates = lookupMembers(baseType, ma.name)
        if (candidates.isEmpty()) {
            reportError("No candidate found"); return true
        }

        // filter by call vs property
        val filtered = candidates.filter { candidateMatchesCallKind(it, ma.isCall) }

        // If call: perform overload applicability check:
        val applicable = filtered.filter { candidate ->
            val subst = createFreshSubstitutionFor(candidate) // fresh typevars for candidate generics
            val constraintsForCandidate = buildConstraintsFromCandidateSignatures(candidate, ma, subst)
            val solverClone = cloneCurrentSolver() // or try solving constraints in temp
            solverClone.addConstraintAll(constraintsForCandidate)
            solverClone.solveBounded() // limited effort
            return solverClone.allConstraintsSatisfied()
        }

        if (applicable.size == 1) {
            // Bind the result type: ma.result <- candidate.returnType (after substitution)
            addConstraint(Equality(ma.result, candidateReturnTypeSubstituted))
            // Also add constraints linking candidate's receiver type to baseType if needed.
            return true
        } else if (applicable.isEmpty()) {
            // keep waiting? or report "no matching overload"
            reportError("no overload found for ${ma.name}")
            return true
        } else {
            // ambiguity: pick most specific (Kotlin rules) or report ambiguous if tie
            val best = pickMostSpecific(applicable)
            if (best != null) {
                addConstraint(Equality(ma.result, best.returnTypeSubstituted))
                return true
            } else {
                reportError("ambiguous call")
                return true
            }
        }
    }

    fun reportError(message: String) {
        throw IllegalStateException(message)
    }*/
}