package zauberKt

interface Or<A, B>
typealias Or3<A, B, C> = Or<A, Or<B, C>>
typealias Or4<A, B, C, D> = Or<A, Or3<B, C, D>>

interface And<A, B>
typealias And3<A, B, C> = And<A, And<B, C>>