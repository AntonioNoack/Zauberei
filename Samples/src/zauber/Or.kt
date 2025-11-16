package zauber

interface Or<A, B>
typealias Or3<A, B, C> = Or<A, Or<B, C>>
typealias Or4<A, B, C, D> = Or<A, Or3<B, C, D>>